package io.eia.platform.audit

import io.eia.shared.kernel.CorrelationId
import io.eia.shared.resilience.trace.TraceParent
import java.time.Instant

/**
 * 記録する監査イベント。「誰が / いつ / 何を / どこへ」(Framework 14.1)。
 *
 * ペイロードの本体は持たない。持てるのは SHA-256([payloadSha256])か参照キー([payloadRef])だけ(ADR-0017)。
 * [details] の値は記録する前に必ず `Masking` を通す([AuditLog.append])。
 *
 * @property occurredAt いつ(業務の事象が起きた時刻)。マイクロ秒に切り捨てて記録する(PostgreSQL の timestamptz の精度)。
 * @property actor 誰が
 * @property action 何を(例 `order.create`)
 * @property target 何に対して(例 `order` / `ord-123`)
 * @property destination どこへ(例 `kafka:sales.order.created.v1`・`partner:acme`)。送り先がなければ null
 */
@Suppress("LongParameterList") // 監査の項目(Framework 14.1。既定値つき。名前付き引数で指定する)
public class AuditEvent(
    public val occurredAt: Instant,
    public val actor: Actor,
    public val action: String,
    public val target: AuditTarget,
    public val outcome: AuditOutcome,
    public val destination: String? = null,
    public val payloadSha256: ChainHash? = null,
    public val payloadRef: String? = null,
    public val correlationId: CorrelationId? = null,
    public val traceparent: TraceParent? = null,
    public val details: Map<String, String> = emptyMap(),
)

public data class Actor(
    public val type: ActorType,
    public val id: String,
)

public enum class ActorType(
    public val code: String,
) {
    USER("user"),
    SERVICE("service"),
    PARTNER("partner"),
    SYSTEM("system"),
}

public data class AuditTarget(
    public val type: String,
    public val id: String? = null,
)

public enum class AuditOutcome(
    public val code: String,
) {
    SUCCESS("success"),
    FAILURE("failure"),
    DENIED("denied"),
}
