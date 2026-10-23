package io.eia.platform.security.token

import io.eia.platform.security.secret.SecretName
import io.ktor.http.Url
import java.net.URI
import kotlin.time.Duration
import io.eia.shared.sdk.http.token.ClientCredentialsConfig as SdkClientCredentialsConfig

/**
 * Client Credentials Grant(RFC 6749 §4.4)の設定(Framework 12.1。ADR-0019 §4)。JVM のサービスの設定の形(`URI` と Secret の名前)で受け取り、
 * KMP の SDK の設定([SdkClientCredentialsConfig]。検証と既定値はそちら。ADR-0037)に変える。
 * 1 回の取得の Timeout・Retry・Circuit Breaker は [ClientCredentialsTokenProvider] に渡す `Resilience` で決める(ADR-0021)。
 *
 * @param tokenEndpoint トークンエンドポイント(Keycloak では `<URL>/realms/<realm>/protocol/openid-connect/token`)
 * @param clientSecret Client Secret の名前。値は [io.eia.platform.security.secret.SecretProvider] から取得する(ハードコードしない)
 * @param scopes 要求するスコープ。空なら `scope` を送らない(IdP の既定のスコープになる)
 * @param refreshBefore 期限のこの時間前から取り直す。寿命が短いトークンでは、寿命の 10% との小さい方を使う
 * @param refreshRetryInterval 期限前の取り直しに失敗したとき、次に取り直すまでの間隔。その間は期限内のトークンを返す
 * @param maxCacheLifetime 1 つのトークンを使い続ける時間の上限。IdP が長い `expires_in` を返しても、この時間で取り直す
 */
@Suppress("LongParameterList") // 設定の項目(既定値つき。名前付き引数で指定する)
public class ClientCredentialsConfig(
    public val tokenEndpoint: URI,
    public val clientId: String,
    public val clientSecret: SecretName,
    public val scopes: Set<String> = emptySet(),
    public val refreshBefore: Duration = SdkClientCredentialsConfig.DEFAULT_REFRESH_BEFORE,
    public val refreshRetryInterval: Duration = SdkClientCredentialsConfig.DEFAULT_REFRESH_RETRY_INTERVAL,
    public val maxCacheLifetime: Duration = SdkClientCredentialsConfig.DEFAULT_MAX_CACHE_LIFETIME,
) {
    init {
        require(tokenEndpoint.scheme in setOf("http", "https") && tokenEndpoint.host != null) {
            "tokenEndpoint は http(s) の絶対 URL にしてください"
        }
    }

    /** SDK の設定(残りの検証はここで行う)。 */
    internal val sdk: SdkClientCredentialsConfig =
        SdkClientCredentialsConfig(
            tokenEndpoint = Url(tokenEndpoint.toString()),
            clientId = clientId,
            scopes = scopes,
            refreshBefore = refreshBefore,
            refreshRetryInterval = refreshRetryInterval,
            maxCacheLifetime = maxCacheLifetime,
        )

    public companion object {
        public val DEFAULT_REFRESH_BEFORE: Duration = SdkClientCredentialsConfig.DEFAULT_REFRESH_BEFORE
        public val DEFAULT_REFRESH_RETRY_INTERVAL: Duration = SdkClientCredentialsConfig.DEFAULT_REFRESH_RETRY_INTERVAL
        public val DEFAULT_MAX_CACHE_LIFETIME: Duration = SdkClientCredentialsConfig.DEFAULT_MAX_CACHE_LIFETIME
    }
}
