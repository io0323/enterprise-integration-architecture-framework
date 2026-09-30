package io.eia.platform.api.idempotency

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * 冪等の設定(ADR-0022 §3)。
 *
 * @property lease 処理中の記録の期限。処理中のままプロセスが落ちても、期限が過ぎれば同じキーの再送で処理を引き継げる。
 *   リクエスト全体の打ち切りの時間(P05 ④b)より長くする。短いと、処理中の要求を別の要求が引き継いで二重に処理しかける
 *   (その場合も、後から完了しようとした側はフェンシングで取り消される)
 * @property retention 完了した応答を保存する期間(INTEGRATION_STANDARDS §2: 24 時間)
 * @property maxStoredBodyBytes 保存する本文の上限。超える応答は保存できないので、業務の更新ごと取り消して 500 にする
 * @property storedHeaders 保存するヘッダ(大文字小文字を区別しない)。ほかのヘッダは再送への応答に含めない
 */
public data class IdempotencyConfig(
    public val lease: Duration = 60.seconds,
    public val retention: Duration = 24.hours,
    public val maxStoredBodyBytes: Int = DEFAULT_MAX_STORED_BODY_BYTES,
    public val storedHeaders: Set<String> = DEFAULT_STORED_HEADERS,
) {
    init {
        require(lease.isPositive()) { "lease は正の値です: $lease" }
        require(retention.isPositive()) { "retention は正の値です: $retention" }
        require(maxStoredBodyBytes > 0) { "maxStoredBodyBytes は正の値です: $maxStoredBodyBytes" }
    }

    public companion object {
        /** 保存する本文の上限の既定値(1 MiB)。 */
        public const val DEFAULT_MAX_STORED_BODY_BYTES: Int = 1_048_576

        /** 応答の内容を表すヘッダだけ。要求ごとに変わるもの(`X-Correlation-Id`・`Date` など)と、`Set-Cookie` は入れない。 */
        public val DEFAULT_STORED_HEADERS: Set<String> =
            setOf("Content-Type", "Content-Language", "Location", "ETag", "Last-Modified", "Cache-Control")

        /**
         * 保存しない状態コード: 5xx・429・408。どれも一時的な失敗で、同じキーで再試行すれば結果が変わりうる。
         * 保存しないときは、業務の更新も取り消す(ADR-0022 §3「保存しない = 副作用なし」)。
         */
        @Suppress("MagicNumber") // HTTP の状態コード
        public fun isStorable(status: Int): Boolean = status < 500 && status != 429 && status != 408
    }
}
