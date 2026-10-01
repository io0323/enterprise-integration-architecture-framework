package io.eia.order.adapters.out.audit

import io.eia.order.adapters.out.persistence.currentTransaction
import io.eia.order.application.port.inbound.RequestedBy
import io.eia.order.application.port.outbound.OrderAuditTrail
import io.eia.order.domain.Order
import io.eia.platform.audit.Actor
import io.eia.platform.audit.ActorType
import io.eia.platform.audit.AuditEvent
import io.eia.platform.audit.AuditMisuse
import io.eia.platform.audit.AuditOutcome
import io.eia.platform.audit.AuditTarget
import io.eia.platform.audit.ChainHash
import io.eia.platform.audit.InvalidAuditEvent
import io.eia.platform.audit.jdbc.AuditLog
import io.eia.platform.audit.jdbc.appendAudit
import io.eia.platform.observability.context.CurrentTrace
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError
import io.eia.shared.kernel.ok
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.time.toJavaInstant

/**
 * 注文の監査の記録(ADR-0017)。`platform/audit` の [AuditLog] で、order の DB の `audit.audit_log` に追記する。
 *
 * - **注文の保存と同じトランザクション**で追記する(ADR-0017 §4)。今のトランザクションがなければ [AuditMisuse] にする
 *   (別のトランザクションで記録すると、業務の更新と記録の確定がずれるため)。
 * - 記録の項目(ADR-0017 §1):
 *   | 項目 | 値 |
 *   |---|---|
 *   | 誰が | `service` / 呼び出し元のクライアント(トークンの `azp`) |
 *   | いつ | 注文の受け付けの時刻(`orderedAt`) |
 *   | 何を | `order.create` / `order` / 注文 ID / `success` |
 *   | 本文の代わり | 要求の本文を正規化した SHA-256(本文そのものは記録しない) |
 *   | 追跡 | 今の処理の Correlation ID と traceparent([CurrentTrace]) |
 */
public class ExposedOrderAuditTrail(
    private val database: Database,
    private val log: AuditLog,
) : OrderAuditTrail {
    override suspend fun orderPlaced(
        order: Order,
        requestedBy: RequestedBy,
        requestDigest: String?,
    ): Result<Unit, DomainError> {
        val transaction =
            database.currentTransaction()
                ?: return err(AuditMisuse("注文の監査は、注文の保存と同じトランザクションの中で記録してください"))
        val trace = CurrentTrace.get()
        val appended =
            digestOf(requestDigest).flatMap { digest ->
                log
                    .appendAudit(transaction, event(order, requestedBy, digest, trace))
                    // AuditError の実装は、すべて DomainError(Retryable か NonRetryable)を実装している
                    .mapError { it as DomainError }
                    .map { }
            }
        // JDBC の呼び出しはコルーチンの打ち切りでは止まらない。打ち切られていれば結果を使わずに伝える(ADR-0024 §3)
        currentCoroutineContext().ensureActive()
        return appended
    }

    private fun digestOf(requestDigest: String?): Result<ChainHash?, DomainError> =
        when (val parsed = requestDigest?.let(ChainHash::parse)) {
            null -> ok(null)
            is Result.Ok -> ok(parsed.value)
            is Result.Err -> err(InvalidAuditEvent("payload_sha256", "SHA-256 の 16 進 64 文字ではありません"))
        }

    private fun event(
        order: Order,
        requestedBy: RequestedBy,
        digest: ChainHash?,
        trace: CurrentTrace,
    ): AuditEvent =
        AuditEvent(
            occurredAt = order.orderedAt.toJavaInstant(),
            actor = Actor(ActorType.SERVICE, requestedBy.clientId),
            action = ACTION_CREATE,
            target = AuditTarget(TARGET_TYPE, order.id.value),
            outcome = AuditOutcome.SUCCESS,
            payloadSha256 = digest,
            correlationId = trace.correlationId,
            traceparent = trace.traceParent,
        )

    private companion object {
        const val ACTION_CREATE = "order.create"
        const val TARGET_TYPE = "order"
    }
}
