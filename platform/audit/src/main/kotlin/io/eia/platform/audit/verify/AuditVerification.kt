package io.eia.platform.audit.verify

import io.eia.platform.audit.AuditError
import io.eia.platform.audit.anchor.AnchorKeys
import io.eia.platform.audit.anchor.AnchorStore
import io.eia.platform.audit.anchor.ServiceName
import io.eia.platform.audit.jdbc.AuditLogReader
import io.eia.platform.audit.jdbc.inReadOnlySnapshot
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
import java.sql.Connection
import java.time.Duration

/**
 * 1 つのサービスの監査記録を検証する: チェーンの検証と、アンカーの全版との照合(`make audit-verify`)。
 *
 * アンカーを先に読み、アンカーの `seq` をチェックポイントにしてからチェーンを読む(チェーンは 1 回だけ読み、全件をメモリに載せない)。
 *
 * チェーンの読み込みと件数の取得は、1 つの REPEATABLE READ の読み取り専用トランザクション(同じスナップショット)で行う。
 * 別々に読むと、検査の間にサービスが追記した分だけ件数が合わず、改竄の疑いと誤って報告するため。
 * アンカーはスナップショットより前に読むので、アンカーの `seq` の記録は必ずスナップショットに含まれる。
 *
 * [connection] は検査専用の接続で、自動コミットが有効であること(呼び出し側のトランザクションを巻き戻さないため)。
 */
public class AuditVerification(
    private val service: ServiceName,
    private val store: AnchorStore,
    minRetention: Duration,
    clockSkew: Duration = AnchorVerifier.DEFAULT_CLOCK_SKEW,
) {
    private val anchorVerifier = AnchorVerifier(service, minRetention, clockSkew)

    @Suppress("ReturnCount") // アンカーの取得・チェーンの読み込みのそれぞれの失敗で返す
    public fun run(connection: Connection): Result<VerificationReport, AuditError> {
        val versions =
            when (val listed = store.listVersions(AnchorKeys.prefix(service))) {
                is Result.Ok -> listed.value
                is Result.Err -> return listed
            }
        val chainVerifier = ChainVerifier(anchorVerifier.checkpoints(versions))
        val (read, tableRows) =
            when (val result = inReadOnlySnapshot(connection) { readChain(connection, chainVerifier) }) {
                is Result.Ok -> result.value
                is Result.Err -> return result
            }
        val chain = chainVerifier.result()
        val countFindings = if (tableRows == read) emptyList() else listOf(Finding.RowCountMismatch(tableRows, read))
        return ok(
            VerificationReport(
                service = service,
                recordCount = chain.count,
                headSeq = chain.headSeq,
                anchorVersionCount = versions.size,
                findings = chain.findings + countFindings + anchorVerifier.verify(versions, chain),
            ),
        )
    }

    /** 読んだ件数と表の件数。 */
    private fun readChain(
        connection: Connection,
        chainVerifier: ChainVerifier,
    ): Result<Pair<Long, Long>, AuditError> =
        AuditLogReader.forEachRow(connection, consumer = chainVerifier::accept).flatMap { read ->
            AuditLogReader.countRows(connection).map { tableRows -> read to tableRows }
        }
}

public data class VerificationReport(
    val service: ServiceName,
    val recordCount: Long,
    val headSeq: Long?,
    val anchorVersionCount: Int,
    val findings: List<Finding>,
) {
    val intact: Boolean get() = findings.isEmpty()
}
