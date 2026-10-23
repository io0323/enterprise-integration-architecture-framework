package io.eia.platform.security.token

import io.eia.platform.security.secret.SecretError
import io.eia.platform.security.secret.SecretMisconfigured
import io.eia.platform.security.secret.SecretNotFound
import io.eia.platform.security.secret.SecretProvider
import io.eia.platform.security.secret.SecretUnreadable
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.eia.shared.sdk.http.token.AccessToken
import io.eia.shared.sdk.http.token.ClientSecret
import io.eia.shared.sdk.http.token.ClientSecretSource
import io.eia.shared.sdk.http.token.TokenError
import io.eia.shared.sdk.http.token.TokenProviderListener
import io.ktor.client.HttpClient
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Instant
import io.eia.shared.sdk.http.token.ClientCredentialsTokenProvider as SdkClientCredentialsTokenProvider

/**
 * Client Credentials Grant でアクセストークンを取得し、期限の少し前まで再利用する(Framework 12.1。ADR-0019 §4)。
 *
 * 取得・キャッシュ・同時の取得のまとめ・Retry・Circuit Breaker・Fallback は KMP の SDK
 * ([SdkClientCredentialsTokenProvider]。ADR-0037)が行う。ここでは JVM のサービスの部品をつなぐだけ:
 * - Client Secret は取得のたびに [SecretProvider] から読む(ローテーションに追従する)。
 * - 出来事は、このクラスのロガーに残す(期限内のトークンを使い続ける・取得できない は WARN、接続できない は DEBUG)。
 *   応答の本文・Client Secret・トークンはログに入れない。
 *
 * **このクラスと [resilience] は、トークンエンドポイントごとに 1 つを作って使い回す。**(状態を持つため。SDK の説明を参照)
 *
 * @param clock 期限の判定に使う時刻(テストでは進められる時計を渡す)
 * @param resilience トークンエンドポイントへの取得を包む [Resilience]。メトリクスを出すときは、`ResilienceMetrics.resilience` で作ったものを渡す
 */
public class ClientCredentialsTokenProvider(
    config: ClientCredentialsConfig,
    httpClient: HttpClient,
    secrets: SecretProvider,
    clock: Clock = Clock.System,
    resilience: Resilience = Resilience(DEFAULT_RESILIENCE_NAME, DEFAULT_RESILIENCE),
) {
    private val delegate =
        SdkClientCredentialsTokenProvider(
            config = config.sdk,
            httpClient = httpClient,
            secrets = secretSource(secrets, config),
            clock = clock,
            resilience = resilience,
            listener = LoggingListener,
        )

    /** 期限内のトークンを返す。なければ取得する。 */
    public suspend fun token(): Result<AccessToken, TokenError> = delegate.token()

    /** 下流が [token] を 401 で拒否したときに呼ぶ。次の [token] で取り直す。 */
    public suspend fun invalidate(token: AccessToken) {
        delegate.invalidate(token)
    }

    public companion object {
        /** 応答の本文の大きさの上限。これを超える応答は解析しない。 */
        public const val MAX_BODY_BYTES: Int = SdkClientCredentialsTokenProvider.MAX_BODY_BYTES

        /** 既定の [Resilience] の名前(メトリクスの `eia.dependency.name`)。 */
        public const val DEFAULT_RESILIENCE_NAME: String = SdkClientCredentialsTokenProvider.DEFAULT_RESILIENCE_NAME

        /** 既定の回復性の設定(試行 5 秒・取得全体の締め切り 10 秒。ADR-0021 §4・§8)。 */
        public val DEFAULT_RESILIENCE: ResilienceConfig = SdkClientCredentialsTokenProvider.DEFAULT_RESILIENCE

        private val logger = LoggerFactory.getLogger(ClientCredentialsTokenProvider::class.java)

        private fun secretSource(
            secrets: SecretProvider,
            config: ClientCredentialsConfig,
        ) = ClientSecretSource {
            secrets
                .get(config.clientSecret)
                .map { ClientSecret(it.reveal()) }
                .mapError(SecretError::asDomainError)
        }

        private object LoggingListener : TokenProviderListener {
            override fun onStaleTokenUsed(
                error: TokenError,
                expiresAt: Instant?,
            ) {
                logger.warn("期限前のトークンの取り直しに失敗したため、期限内のトークンを使い続けます code={} expiresAt={}", error.code, expiresAt)
            }

            override fun onFetchFailed(error: TokenError) {
                logger.warn("トークンを取得できません code={} {}", error.code, error.message)
            }

            override fun onUnreachable(exceptionType: String?) {
                logger.debug("トークンエンドポイントに接続できません exception={}", exceptionType)
            }
        }
    }
}

/** SecretProvider のエラーを [DomainError] にする(各実装は Retryable か NonRetryable のどちらか)。 */
private fun SecretError.asDomainError(): DomainError =
    when (this) {
        is SecretNotFound -> this
        is SecretMisconfigured -> this
        is SecretUnreadable -> this
    }
