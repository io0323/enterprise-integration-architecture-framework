package io.eia.iotbridge.domain

import io.eia.shared.kernel.DomainError

/** テレメトリを受け付けない理由(DLQ の `eiaf.dlq.reason`。ADR-0039 §4)。種類の数は有限にする(メトリクスのラベルにも使う)。 */
public enum class RejectionReason {
    /** 知らない形式の版(デコーダのない版。版をやめた後に届いたものを含む) */
    UNSUPPORTED_PAYLOAD_VERSION,

    /** JSON として読めない・形式のスキーマ(contracts/devices)に合わない */
    INVALID_PAYLOAD,

    /** トピックが `devices/{tenant}/{deviceId}/telemetry` の形ではない */
    INVALID_TOPIC,
}

/**
 * 受け付けないテレメトリ(決定的な誤りなので、リトライせずに DLQ に隔離する。捨てない。ADR-0039 §4)。
 * [detail] には項目の名前と破った規則だけを入れ、**値は入れない**(ペイロードの全文のログの禁止)。
 */
public data class TelemetryRejection(
    public val reason: RejectionReason,
    public val detail: String,
) : DomainError.NonRetryable {
    override val code: String get() = reason.name.lowercase()
    override val message: String get() = "${reason.name}: $detail"
}
