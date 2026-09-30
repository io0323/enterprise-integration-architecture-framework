package io.eia.shared.resilience

import io.eia.shared.kernel.DomainError
import kotlin.time.Duration

/**
 * 回復性の部品が返すエラー(ADR-0021 §2)。どれも依存先が一時的に使えないことを表すので [DomainError.Retryable] とする。
 * 呼び出し元(サービスの入口)は 503 と `Retry-After` などに写せる。
 *
 * メッセージには依存先の名前と設定値だけを入れ、要求の内容は入れない。
 */
public sealed interface ResilienceError : DomainError.Retryable {
    /** 依存先の名前([Resilience.name])。メトリクスとログの属性にも使う。 */
    public val name: String
}

/**
 * 依存先に送らずに、手元で断った呼び出し([CircuitOpen] と [BulkheadFull])。
 *
 * 依存先の状態を表さないので、Circuit Breaker の失敗にもリトライバジェットにも数えず、リトライもしない(ADR-0021 §3)。
 */
public sealed interface ResilienceRejection : ResilienceError

/**
 * Circuit Breaker が Open(または Half-Open で試す枠が埋まっている)ので呼び出さなかった。
 *
 * @property retryAfter Open が明けるまでの残り時間。Half-Open で枠が埋まっているときは `null`。
 */
public data class CircuitOpen(
    override val name: String,
    override val retryAfter: Duration?,
) : ResilienceRejection {
    override val code: String get() = "circuit_open"
    override val message: String get() = "$name の Circuit Breaker が開いています"
}

/** Bulkhead の同時実行数の上限に達し、[BulkheadConfig.maxWait] の間に空かなかった。 */
public data class BulkheadFull(
    override val name: String,
) : ResilienceRejection {
    override val code: String get() = "bulkhead_full"
    override val message: String get() = "$name の同時実行数が上限に達しています"
}

/** 1 回の試行が [ResilienceConfig.attemptTimeout] を超えた。Circuit Breaker の失敗に数え、リトライの対象にする。 */
public data class AttemptTimedOut(
    override val name: String,
    public val timeout: Duration,
) : ResilienceError {
    override val code: String get() = "timeout"
    override val message: String get() = "$name の呼び出しが $timeout でタイムアウトしました"
}

/** リトライを含む呼び出し全体が締め切り(タイムバジェット。Framework 13.1)を超えた。 */
public data class DeadlineExceeded(
    override val name: String,
    public val deadline: Duration,
) : ResilienceError {
    override val code: String get() = "deadline_exceeded"
    override val message: String get() = "$name の呼び出しが締め切り $deadline を超えました"
}
