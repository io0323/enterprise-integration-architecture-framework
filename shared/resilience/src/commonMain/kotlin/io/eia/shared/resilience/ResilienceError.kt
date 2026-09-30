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

/**
 * リトライを含む呼び出し全体が締め切り(タイムバジェット。Framework 13.1)を超えた。
 *
 * @property deadline この呼び出しの予算。自分の締め切りと、呼び出し元の締め切り([CallDeadline])の残り時間の短い方(ADR-0021 §12)。
 * @property source 予算を決めたのが自分の締め切りか、呼び出し元の締め切りか。Circuit Breaker とリトライバジェットの数え方が変わる
 */
public data class DeadlineExceeded(
    override val name: String,
    public val deadline: Duration,
    public val source: DeadlineSource = DeadlineSource.OWN,
) : ResilienceError {
    override val code: String get() = "deadline_exceeded"
    override val message: String
        get() =
            when (source) {
                DeadlineSource.OWN -> "$name の呼び出しが締め切り $deadline を超えました"
                DeadlineSource.CALLER -> "$name の呼び出しが呼び出し元の締め切り(予算 $deadline)を超えました"
            }
}

/** [DeadlineExceeded] の予算を決めたもの(ADR-0021 §12)。 */
public enum class DeadlineSource {
    /**
     * この [Resilience] の締め切り([ResilienceConfig.deadline] か [Resilience.execute] の引数)。
     * 打ち切った試行は、依存先が期限内に応答しなかった失敗として、Circuit Breaker とリトライバジェットに数える(ADR-0021 §1)。
     */
    OWN,

    /**
     * 呼び出し元の締め切り([CallDeadline])の残り時間。`attemptTimeout` より前に打ち切った試行は、Circuit Breaker にも
     * リトライバジェットにも数えない。依存先の状態ではなく、呼び出し元の残り時間(負荷で減りうる)を表すため。
     */
    CALLER,
}
