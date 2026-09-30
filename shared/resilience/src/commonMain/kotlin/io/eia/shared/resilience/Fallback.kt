package io.eia.shared.resilience

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 最終的なエラーに対する代替動作(Framework 13.1: キャッシュ応答 / 既定値 / 縮退 / 後回し)。ADR-0021 §9。
 *
 * 暗黙の既定値は持たない。代替動作は業務ごとに決めるもので、呼び出し側が [Resilience.execute] に明示的に渡す。
 *
 * @property appliesTo 代替動作を使うエラーか。業務エラー(NonRetryable)まで隠さないよう、対象は呼び出し側が選ぶ
 * @property handler 代替の結果。代替もできなければ Err を返してよい
 */
public class Fallback<T>(
    public val appliesTo: (DomainError) -> Boolean,
    public val handler: suspend (DomainError) -> Result<T, DomainError>,
) {
    public companion object {
        /** 依存先が一時的に使えないとき(Retryable。遮断・タイムアウト・Bulkhead の拒否を含む)に [handler] を使う。 */
        public fun <T> whenUnavailable(handler: suspend (DomainError) -> Result<T, DomainError>): Fallback<T> =
            Fallback({ it is DomainError.Retryable }, handler)
    }
}
