@file:Suppress("MagicNumber") // 鍵の長さ・トークンの寿命・乱数のバイト数

package io.eia.order.app

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import io.eia.platform.testsupport.InfraImages
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.sql.Connection
import java.util.Date
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * app の統合テストの環境: ローカル基盤と同じロール(所有者 `order_service` と `order_service_app`)の PostgreSQL と、テスト用の IdP。
 * 環境変数は、migrate と serve で別々に作る(serve には所有者のパスワードを渡さない。ADR-0024 §2)。
 */
internal class AppEnvironment : AutoCloseable {
    val postgres: PostgreSQLContainer = PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
    private val ownerPassword = randomHex()
    private val appPassword = randomHex()
    private val databases = AtomicInteger()
    private val key: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()
    private val jwks: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/jwks") { exchange ->
                val body = JWKSet(key.toPublicJWK()).toString().toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

    fun start() {
        postgres.start()
        superuser(postgres.databaseName) {
            it.createStatement().use { s ->
                s.execute("CREATE ROLE order_service LOGIN PASSWORD '$ownerPassword'")
                s.execute("CREATE ROLE order_service_app LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION")
            }
        }
    }

    /** DB を 1 つ作る(マイグレーションはしない。migrate のコマンドで行う)。 */
    fun newDatabase(): String {
        val name = "order_app_it_${databases.incrementAndGet()}"
        superuser(postgres.databaseName) {
            it.createStatement().use { s ->
                s.execute("CREATE DATABASE $name OWNER order_service")
                s.execute("REVOKE ALL ON DATABASE $name FROM PUBLIC")
                s.execute("GRANT CONNECT ON DATABASE $name TO order_service_app")
            }
        }
        return name
    }

    private fun url(database: String): String = postgres.jdbcUrl.replaceAfterLast('/', database)

    /** migrate のコマンドの環境(所有者のパスワードだけ)。 */
    fun migrateEnv(database: String): Map<String, String> = mapOf("ORDER_DB_URL" to url(database), "ORDER_DB_PASSWORD" to ownerPassword)

    /** serve のコマンドの環境(アプリのロールのパスワードだけ。ポートは空いているものを使う)。 */
    fun serveEnv(
        database: String,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> =
        mapOf(
            "ORDER_DB_URL" to url(database),
            "ORDER_APP_DB_PASSWORD" to appPassword,
            "ORDER_HTTP_PORT" to "0",
            "OIDC_ISSUER" to ISSUER,
            "OIDC_JWKS_URI" to "http://127.0.0.1:${jwks.address.port}/jwks",
            "EIA_LOG_FORMAT" to "console",
        ) + extra

    fun token(clientId: String = "client-a"): String {
        val now = Clock.System.now()
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(ISSUER)
                .audience("order-api")
                .subject("service-account-$clientId")
                .claim("azp", clientId)
                .claim("scope", "sales.order:read sales.order:write")
                .issueTime(Date(now.toEpochMilliseconds()))
                .expirationTime(Date((now + 5.minutes).toEpochMilliseconds()))
                .build()
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .keyID(key.keyID)
                .type(JOSEObjectType.JWT)
                .build()
        return SignedJWT(header, claims).apply { sign(RSASSASigner(key)) }.serialize()
    }

    fun <T> superuser(
        database: String,
        block: (Connection) -> T,
    ): T =
        PGSimpleDataSource()
            .apply {
                setURL(url(database))
                user = postgres.username
                password = postgres.password
            }.connection
            .use(block)

    fun count(
        database: String,
        sql: String,
    ): Long =
        superuser(database) { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> rs.next().let { rs.getLong(1) } }
            }
        }

    override fun close() {
        jwks.stop(0)
        postgres.stop()
    }

    companion object {
        const val ISSUER = "http://idp.test/realms/eiaf"

        private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))
    }
}
