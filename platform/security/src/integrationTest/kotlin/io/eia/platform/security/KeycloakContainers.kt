package io.eia.platform.security

import io.eia.platform.security.secret.SecretName
import io.eia.platform.testsupport.InfraImages
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger

internal const val KEYCLOAK_HTTP_PORT = 8080
private const val MANAGEMENT_PORT = 9000
private const val REALM_IMPORT = "/opt/keycloak/data/import/realm-eiaf.json"
private const val SECRET_BYTES = 16
private const val STARTUP_TIMEOUT_MINUTES = 3L
private val STARTUP_TIMEOUT: Duration = Duration.ofMinutes(STARTUP_TIMEOUT_MINUTES)

/**
 * compose の `KC_HOSTNAME` は `http://localhost:19180` に固定している(ADR-0016 §7)。
 * テストではホスト側のポートが毎回変わるため、`KC_HOSTNAME` を上書きして iss を固定し、
 * トークンと JWKS はバックチャネル(`KC_HOSTNAME_BACKCHANNEL_DYNAMIC`)として、要求を受けた URL から取得する。
 * iss(frontend の URL)と JWKS の取得先(バックチャネルの URL)を別々に設定できることの確認にもなる(ADR-0019 §3)。
 */
internal const val FRONTEND_URL = "http://keycloak.it:8080"
internal const val ISSUER = "$FRONTEND_URL/realms/eiaf"
internal const val CLIENT_ID = "eiaf-e2e"
internal val SECRET_NAME = SecretName("EIAF_E2E_CLIENT_SECRET")

/** `infra/local/keycloak/realm-eiaf.json`(images.env と同じ infra/local から探す)。 */
private fun realmFile(): Path =
    Path
        .of(System.getProperty(InfraImages.FILE_PROPERTY))
        .toAbsolutePath()
        .parent
        .resolve("keycloak/realm-eiaf.json")

/**
 * Client Secret はテストごとに乱数で作る。RFC 6749 §2.3.1 の form-urlencoded を実際の IdP で確かめるため、
 * エンコードが必要な記号(`:` `+` `/` `%` 空白 `~` `*`)を含める。JSON の realm に埋め込まれるため `"` と `\` は使わない。
 */
internal fun newClientSecret(): String = randomHex() + ":+/% ~*"

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes))

/** realm-eiaf を読み込んだ Keycloak(イメージは InfraImages の KEYCLOAK_IMAGE)。 */
internal fun keycloakContainer(clientSecret: String): GenericContainer<*> =
    GenericContainer(InfraImages.get("KEYCLOAK_IMAGE"))
        .withCommand("start-dev", "--import-realm")
        .withEnv("KC_HOSTNAME", FRONTEND_URL)
        .withEnv("KC_HOSTNAME_BACKCHANNEL_DYNAMIC", "true")
        .withEnv("KC_HEALTH_ENABLED", "true")
        .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
        .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", randomHex())
        .withEnv("EIAF_E2E_CLIENT_SECRET", clientSecret)
        .withCopyFileToContainer(MountableFile.forHostPath(realmFile()), REALM_IMPORT)
        .withExposedPorts(KEYCLOAK_HTTP_PORT, MANAGEMENT_PORT)
        .waitingFor(Wait.forHttp("/health/ready").forPort(MANAGEMENT_PORT).withStartupTimeout(STARTUP_TIMEOUT))

/** 送った要求の数を数える CIO のクライアント。リトライと Circuit Breaker で、IdP に届いた要求の数を確かめるために使う。 */
internal class CountingHttpClient : AutoCloseable {
    private val count = AtomicInteger()

    val client: HttpClient =
        HttpClient(CIO).apply {
            plugin(HttpSend).intercept { request ->
                count.incrementAndGet()
                execute(request)
            }
        }

    /** これまでに送った要求の数。 */
    val sent: Int get() = count.get()

    override fun close() = client.close()
}
