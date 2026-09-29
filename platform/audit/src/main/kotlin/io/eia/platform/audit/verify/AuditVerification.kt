package io.eia.platform.audit.verify

import io.eia.platform.audit.AuditError
import io.eia.platform.audit.anchor.AnchorKeys
import io.eia.platform.audit.anchor.AnchorStore
import io.eia.platform.audit.anchor.ServiceName
import io.eia.platform.audit.jdbc.AuditLogReader
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok
import java.sql.Connection
import java.time.Duration

/**
 * 1 つのサービスの監査記録を検証する: チェーンの検証と、アンカーの全版との照合(`make audit-verify`)。
 *
 * アンカーを先に読み、アンカーの `seq` をチェックポイントにしてからチェーンを読む(チェーンは 1 回だけ読み、全件をメモリに載せない)。
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
        when (val read = AuditLogReader.forEachRow(connection, consumer = chainVerifier::accept)) {
            is Result.Ok -> Unit
            is Result.Err -> return read
        }
        val chain = chainVerifier.result()
        return ok(
            VerificationReport(
                service = service,
                recordCount = chain.count,
                headSeq = chain.headSeq,
                anchorVersionCount = versions.size,
                findings = chain.findings + anchorVerifier.verify(versions, chain),
            ),
        )
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
