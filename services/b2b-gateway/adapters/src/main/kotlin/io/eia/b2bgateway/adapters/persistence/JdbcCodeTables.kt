package io.eia.b2bgateway.adapters.persistence

import io.eia.b2bgateway.application.port.outbound.CodeLookup
import io.eia.b2bgateway.application.port.outbound.CodeMappings
import io.eia.b2bgateway.application.port.outbound.CodeTableSource
import io.eia.b2bgateway.application.port.outbound.CodeTableStore
import io.eia.b2bgateway.domain.codes.CodeTable
import io.eia.b2bgateway.domain.order.CodeResolution
import io.eia.b2bgateway.domain.order.InternalCustomer
import io.eia.b2bgateway.domain.order.InternalItem
import io.eia.b2bgateway.domain.order.ItemCode
import io.eia.b2bgateway.domain.order.ShippingAddress
import io.eia.platform.audit.Actor
import io.eia.platform.audit.ActorType
import io.eia.platform.audit.AuditEvent
import io.eia.platform.audit.AuditOutcome
import io.eia.platform.audit.AuditTarget
import io.eia.platform.audit.ChainHash
import io.eia.platform.audit.jdbc.AuditLog
import io.eia.platform.observability.context.CurrentTrace
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Clock
import javax.sql.DataSource

/**
 * 取引先ごとのコードの対応表(表 `b2b.code_table`・`buyer_code`・`delivery_party_code`・`item_code`。ADR-0040 §6)。
 * 入れ替えは、版を進める(行を押さえて、同じ表の同時の入れ替えを順番にする)→ 古い行を消す → 新しい行を入れる → 監査を、1 つのトランザクションで書く。
 *
 * 監査の記録の項目
 * | 項目 | 値 |
 * |---|---|
 * | 誰が | `user` / コマンドを実行した運用者 |
 * | 何を | `b2b.codes.import` / `b2b_code_table` / `{取引先の ID}/{種類}` / `success` |
 * | 本文の代わり | 取り込んだファイルの SHA-256 |
 * | 補足 | 取引先の ID・種類・入れ替えた後の版・行の数 |
 */
public class JdbcCodeTables(
    private val dataSource: DataSource,
    private val log: AuditLog,
    private val clock: Clock = Clock.systemUTC(),
) : CodeMappings,
    CodeTableStore {
    override suspend fun resolve(
        partnerId: String,
        lookup: CodeLookup,
    ): Result<CodeResolution, DomainError> =
        dataSource.inTransaction { connection ->
            val buyers =
                select(connection, SELECT_BUYERS, partnerId, listOf(lookup.buyers)) { rs ->
                    rs.getString("gln") to InternalCustomer(rs.getString("customer_id"), rs.address())
                }
            val parties =
                select(connection, SELECT_DELIVERY_PARTIES, partnerId, listOf(lookup.deliveryParties)) { rs ->
                    rs.getString("gln") to
                        rs.address()
                }
            val gtins =
                lookup.items
                    .filterIsInstance<ItemCode.Gtin>()
                    .map { it.value }
                    .toSet()
            val partNumbers =
                lookup.items
                    .filterIsInstance<ItemCode.BuyerPartNumber>()
                    .map { it.value }
                    .toSet()
            val items =
                select(connection, SELECT_ITEMS, partnerId, listOf(gtins, partNumbers)) { rs ->
                    val code = rs.getString("code")
                    val item = if (rs.getString("code_type") == GTIN) ItemCode.Gtin(code) else ItemCode.BuyerPartNumber(code)
                    item to InternalItem(rs.getString("product_id"), rs.getString("sku"))
                }
            CodeResolution(buyers, parties, items)
        }

    override suspend fun replace(
        partnerId: String,
        table: CodeTable,
        source: CodeTableSource,
    ): Result<Int, DomainError> {
        val trace = CurrentTrace.get()
        // 同じ表を 2 つのコマンドが同時に初めて入れ替えたとき(主キーの違反)は、やり直せば版を進める側になる
        return dataSource.inTransaction(retryable = setOf(UNIQUE_VIOLATION)) { connection ->
            val version =
                connection.prepareStatement(NEXT_VERSION).use { statement ->
                    var index = 0
                    statement.setString(++index, partnerId)
                    statement.setString(++index, table.kind.label)
                    statement.setInt(++index, table.size)
                    statement.setString(++index, source.sha256)
                    statement.setString(++index, source.operator)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            insertRows(connection, partnerId, table)
            val event = auditEvent(partnerId, table, source, version, trace)
            when (val appended = log.append(connection, event)) {
                is Result.Ok -> Unit

                // 追記できなければ、入れ替えも残さない(ADR-0017 §4)
                is Result.Err -> throw AuditFailed(appended.error.code, appended.error is DomainError.Retryable)
            }
            version
        }
    }

    private fun insertRows(
        connection: Connection,
        partnerId: String,
        table: CodeTable,
    ) {
        val (target, insert) =
            when (table) {
                is CodeTable.Buyers -> "buyer_code" to INSERT_BUYER
                is CodeTable.DeliveryParties -> "delivery_party_code" to INSERT_DELIVERY_PARTY
                is CodeTable.Items -> "item_code" to INSERT_ITEM
            }
        connection.prepareStatement("DELETE FROM b2b.$target WHERE partner_id = ?").use { statement ->
            statement.setString(1, partnerId)
            statement.executeUpdate()
        }
        connection.prepareStatement(insert).use { statement ->
            when (table) {
                is CodeTable.Buyers -> {
                    table.entries.forEach { (gln, customer) ->
                        var index = 0
                        statement.setString(++index, partnerId)
                        statement.setString(++index, gln)
                        statement.setString(++index, customer.customerId)
                        statement.setAddress(++index, customer.defaultAddress)
                        statement.addBatch()
                    }
                }

                is CodeTable.DeliveryParties -> {
                    table.entries.forEach { (gln, address) ->
                        var index = 0
                        statement.setString(++index, partnerId)
                        statement.setString(++index, gln)
                        statement.setAddress(++index, address)
                        statement.addBatch()
                    }
                }

                is CodeTable.Items -> {
                    table.entries.forEach { (code, item) ->
                        var index = 0
                        statement.setString(++index, partnerId)
                        statement.setString(++index, if (code is ItemCode.Gtin) GTIN else BUYER_PART_NUMBER)
                        statement.setString(++index, code.value)
                        statement.setString(++index, item.productId)
                        statement.setString(++index, item.sku)
                        statement.addBatch()
                    }
                }
            }
            statement.executeBatch()
        }
    }

    private fun auditEvent(
        partnerId: String,
        table: CodeTable,
        source: CodeTableSource,
        version: Int,
        trace: CurrentTrace,
    ): AuditEvent =
        AuditEvent(
            occurredAt = clock.instant(),
            actor = Actor(ActorType.USER, source.operator),
            action = ACTION,
            target = AuditTarget(TARGET_TYPE, "$partnerId/${table.kind.label}"),
            outcome = AuditOutcome.SUCCESS,
            payloadSha256 = (ChainHash.parse(source.sha256) as Result.Ok).value,
            correlationId = trace.correlationId,
            traceparent = trace.traceParent,
            details =
                mapOf(
                    "partner_id" to partnerId,
                    "kind" to table.kind.label,
                    "version" to version.toString(),
                    "rows" to table.size.toString(),
                ),
        )

    /** 取引先の ID の後に、[codes] をそれぞれ配列の引数にして引く。どれも空なら DB に行かない。 */
    private fun <K, V> select(
        connection: Connection,
        sql: String,
        partnerId: String,
        codes: List<Set<String>>,
        row: (ResultSet) -> Pair<K, V>,
    ): Map<K, V> {
        if (codes.all { it.isEmpty() }) return emptyMap()
        return connection.prepareStatement(sql).use { statement ->
            var index = 0
            statement.setString(++index, partnerId)
            codes.forEach { statement.setArray(++index, connection.createArrayOf("text", it.toTypedArray())) }
            statement.executeQuery().use { rs -> buildMap { while (rs.next()) row(rs).let { put(it.first, it.second) } } }
        }
    }

    private fun ResultSet.address(): ShippingAddress =
        ShippingAddress(
            countryCode = getString("country_code"),
            postalCode = getString("postal_code"),
            region = getString("region"),
            city = getString("city"),
            line1 = getString("line1"),
            line2 = getString("line2"),
        )

    /** 住所の 6 列を [first] から順に入れる。 */
    private fun PreparedStatement.setAddress(
        first: Int,
        address: ShippingAddress,
    ) {
        var index = first
        setString(index++, address.countryCode)
        setString(index++, address.postalCode)
        setString(index++, address.region)
        setString(index++, address.city)
        setString(index++, address.line1)
        setString(index, address.line2)
    }

    private companion object {
        const val ACTION = "b2b.codes.import"
        const val TARGET_TYPE = "b2b_code_table"
        const val GTIN = "gtin"
        const val BUYER_PART_NUMBER = "buyer_part_number"
        const val ADDRESS = "country_code, postal_code, region, city, line1, line2"

        const val SELECT_BUYERS = "SELECT gln, customer_id, $ADDRESS FROM b2b.buyer_code WHERE partner_id = ? AND gln = ANY(?)"
        const val SELECT_DELIVERY_PARTIES = "SELECT gln, $ADDRESS FROM b2b.delivery_party_code WHERE partner_id = ? AND gln = ANY(?)"
        const val SELECT_ITEMS = """
            SELECT code_type, code, product_id, sku FROM b2b.item_code
            WHERE partner_id = ? AND ((code_type = 'gtin' AND code = ANY(?)) OR (code_type = 'buyer_part_number' AND code = ANY(?)))
        """
        const val NEXT_VERSION = """
            INSERT INTO b2b.code_table (partner_id, kind, version, rows, source_sha256, imported_by) VALUES (?, ?, 1, ?, ?, ?)
            ON CONFLICT (partner_id, kind) DO UPDATE SET version = b2b.code_table.version + 1, rows = EXCLUDED.rows,
              source_sha256 = EXCLUDED.source_sha256, imported_by = EXCLUDED.imported_by, imported_at = clock_timestamp()
            RETURNING version
        """
        const val INSERT_BUYER = """
            INSERT INTO b2b.buyer_code (partner_id, gln, customer_id, $ADDRESS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val INSERT_DELIVERY_PARTY = "INSERT INTO b2b.delivery_party_code (partner_id, gln, $ADDRESS) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        const val INSERT_ITEM = "INSERT INTO b2b.item_code (partner_id, code_type, code, product_id, sku) VALUES (?, ?, ?, ?, ?)"
    }
}
