package io.eia.shared.sdk.http.token

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Client Credentials Grant(RFC 6749 §4.4)の設定(Framework 12.1。ADR-0019 §4・ADR-0037)。
 * 1 回の取得の Timeout・Retry・Circuit Breaker は [ClientCredentialsTokenProvider] に渡す `Resilience` で決める(ADR-0021)。
 * Client Secret は設定に持たず、[ClientSecretSource] から取得のたびに読む(ハードコードしない)。
 *
 * @param tokenEndpoint トークンエンドポイント(Keycloak では `<URL>/realms/<realm>/protocol/openid-connect/token`)
 * @param scopes 要求するスコープ。空なら `scope` を送らない(IdP の既定のスコープになる)
 * @param refreshBefore 期限のこの時間前から取り直す。寿命が短いトークンでは、寿命の 10% との小さい方を使う
 * @param refreshRetryInterval 期限前の取り直しに失敗したとき、次に取り直すまでの間隔。その間は期限内のトークンを返す
 * @param maxCacheLifetime 1 つのトークンを使い続ける時間の上限。IdP が長い `expires_in` を返しても、この時間で取り直す
 *   (Framework 12.1「トークンは短命(≦1h)」。受信側の `JwtVerifierConfig.maxTokenLifetime` と同じ既定値)
 */
public class ClientCredentialsConfig(
    public val tokenEndpoint: Url,
    public val clientId: String,
    public val scopes: Set<String> = emptySet(),
    public val refreshBefore: Duration = DEFAULT_REFRESH_BEFORE,
    public val refreshRetryInterval: Duration = DEFAULT_REFRESH_RETRY_INTERVAL,
    public val maxCacheLifetime: Duration = DEFAULT_MAX_CACHE_LIFETIME,
) {
    init {
        require(tokenEndpoint.protocol in setOf(URLProtocol.HTTP, URLProtocol.HTTPS) && tokenEndpoint.host.isNotEmpty()) {
            "tokenEndpoint は http(s) の絶対 URL にしてください"
        }
        require(clientId.isNotEmpty()) { "clientId が空です" }
        scopes.forEach(ScopeToken::require)
        require(!refreshBefore.isNegative()) { "refreshBefore は 0 以上にしてください" }
        require(refreshRetryInterval.isPositive()) { "refreshRetryInterval は正の値にしてください" }
        require(maxCacheLifetime.isPositive()) { "maxCacheLifetime は正の値にしてください" }
    }

    public companion object {
        public val DEFAULT_REFRESH_BEFORE: Duration = 30.seconds
        public val DEFAULT_REFRESH_RETRY_INTERVAL: Duration = 5.seconds
        public val DEFAULT_MAX_CACHE_LIFETIME: Duration = 1.hours
    }
}
