package io.eia.fileexchange.adapters.persistence

import io.eia.fileexchange.application.port.outbound.PastDeliveries
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import java.sql.Timestamp
import javax.sql.DataSource
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * 切り替えより前の配送の記録(表 `exchange.delivery`。P12 ⑦b から読み取り専用。ADR-0032 §8)。
 * 1 回きりの移行で、archive に収めたのに送り終えていないファイルを探す。配送の記録がないファイル(配送を始める前に落ちた・受け取る取引先が
 * いなかった)も対象にする(受け取る取引先は b2b-gateway が台帳で決める。いなければ送らずに、保存期間で消す)。
 */
public class JdbcPastDeliveries(
    private val dataSource: DataSource,
) : PastDeliveries {
    override suspend fun unsent(since: Instant): Result<List<String>, DomainError> =
        dataSource.inTransaction { connection ->
            connection.prepareStatement(UNSENT).use { statement ->
                statement.setTimestamp(1, Timestamp.from(since.toJavaInstant()))
                statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }

    private companion object {
        const val UNSENT = """
            SELECT r.file_name FROM exchange.received_file r
            WHERE r.state = 'STORED' AND r.destination = 'ARCHIVE' AND r.stored_at >= ?
              AND (NOT EXISTS (SELECT 1 FROM exchange.delivery d WHERE d.file_name = r.file_name)
                OR EXISTS (SELECT 1 FROM exchange.delivery d WHERE d.file_name = r.file_name AND d.state <> 'SENT'))
            ORDER BY r.file_name
        """
    }
}
