@file:Suppress("MagicNumber") // 有効期間

package io.eia.platform.security.tls

import io.eia.platform.testsupport.TestPki
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.engine.ConnectorType
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import javax.net.ssl.SSLContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

private val variables = TlsVariables("TEST")

private fun files(
    pki: TestPki,
    server: TestPki.Issued,
) = TlsFiles(variables, server.certFile.toString(), server.keyFile.toString(), pki.caFile.toString())

private fun Result<ServerTls, ValidationError>.loaded(): ServerTls = shouldBeInstanceOf<Result.Ok<ServerTls>>().value

private fun Result<ServerTls, ValidationError>.refused(): String = shouldBeInstanceOf<Result.Err<ValidationError>>().error.message

/** JDK の HttpClient(ゲートウェイとの間と同じ HTTP/1.1)で GET し、状態コードを返す。 */
private fun get(
    url: String,
    context: SSLContext?,
): Int {
    val client =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .apply { context?.let(::sslContext) }
            .build()
    val request =
        HttpRequest
            .newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
    return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
}

/** API のポートの mTLS と、ヘルスチェックのポートの分け方(ADR-0024 §6)。 */
class MutualTlsSpec :
    FunSpec({
        val pki = TestPki()
        val serverCert = pki.issue("service", listOf("localhost"))
        lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>

        fun port(type: ConnectorType): Int =
            runBlocking {
                server.engine
                    .resolvedConnectors()
                    .first { it.type == type }
                    .port
            }

        fun api() = "https://localhost:${port(ConnectorType.HTTPS)}/v1/ping"

        beforeSpec {
            val tls = ServerTls.load(files(pki, serverCert)).loaded()
            server =
                embeddedServer(Netty, configure = { mutualTlsConnectors(0, 0, tls, setOf("apisix")) }) {
                    routing {
                        overPlaintext { get("/health/live") { call.respondText("UP") } }
                        overTls { get("/v1/ping") { call.respondText("pong") } }
                    }
                }.start(wait = false)
        }
        afterSpec {
            server.stop(0, 0)
            pki.close()
        }

        context("API のポート(mTLS)") {
            test("許可の一覧の SAN(apisix)のクライアント証明書なら届く") {
                get(api(), pki.issue("apisix", listOf("apisix")).clientContext(pki)) shouldBe 200
            }

            test("クライアント証明書のない接続は拒否する") {
                shouldThrow<IOException> { get(api(), pki.anonymousClientContext()) }
            }

            test("別の CA が署名した証明書は、SAN が apisix でも拒否する") {
                TestPki("Other CA").use { other ->
                    shouldThrow<IOException> { get(api(), other.issue("apisix", listOf("apisix")).clientContext(pki)) }
                }
            }

            test("同じ CA の証明書でも、SAN が許可の一覧にないものは拒否する") {
                shouldThrow<IOException> { get(api(), pki.issue("other-service", listOf("other-service")).clientContext(pki)) }
            }

            test("期限切れのクライアント証明書は拒否する") {
                val expired = pki.issue("apisix", listOf("apisix"), validFrom = Clock.System.now() - 40.days)
                shouldThrow<IOException> { get(api(), expired.clientContext(pki)) }
            }

            test("平文の HTTP では応答しない") {
                shouldThrow<IOException> { get("http://127.0.0.1:${port(ConnectorType.HTTPS)}/v1/ping", null) }
            }
        }

        context("ヘルスチェックのポート(平文)") {
            test("ヘルスチェックは平文のポートだけで返し、API のルートは平文のポートにない") {
                val health = port(ConnectorType.HTTP)
                get("http://127.0.0.1:$health/health/live", null) shouldBe 200
                get("http://127.0.0.1:$health/v1/ping", null) shouldBe 404
                get(
                    "https://localhost:${port(ConnectorType.HTTPS)}/health/live",
                    pki.issue("apisix", listOf("apisix")).clientContext(pki),
                ) shouldBe
                    404
            }
        }

        context("起動時の証明書の確認") {
            test("ファイルの設定がなければ、足りない環境変数の名前を全部示す") {
                val message = ServerTls.load(TlsFiles(variables, null, null, null)).refused()
                listOf("TEST_TLS_CERT_FILE", "TEST_TLS_KEY_FILE", "TEST_TLS_CLIENT_CA_FILE").forEach { message shouldContain it }
            }

            test("サーバ証明書が期限切れなら、原因と直し方(make certs)を示す") {
                val expired = pki.issue("service", listOf("localhost"), validFrom = Clock.System.now() - 40.days)
                val message = ServerTls.load(files(pki, expired)).refused()
                message shouldContain "TEST_TLS_CERT_FILE"
                message shouldContain "有効期限が切れています"
                message shouldContain "make certs"
            }

            test("まだ有効でない証明書は拒否する") {
                val future = pki.issue("service", listOf("localhost"), validFrom = Clock.System.now() + 1.days)
                ServerTls.load(files(pki, future)).refused() shouldContain "まだ有効ではありません"
            }

            test("クライアント証明書を検証する CA が期限切れなら拒否する") {
                TestPki("Expired CA", caValidFrom = Clock.System.now() - 40.days).use { expired ->
                    val message = ServerTls.load(files(pki, serverCert).copy(clientCa = expired.caFile.toString())).refused()
                    message shouldContain "TEST_TLS_CLIENT_CA_FILE"
                    message shouldContain "有効期限が切れています"
                }
            }

            test("鍵が PKCS#8 の PEM でない・ファイルがない") {
                val broken = Files.createTempFile("broken", ".key").also { Files.writeString(it, "not a key") }
                try {
                    val message = ServerTls.load(files(pki, serverCert).copy(key = broken.toString())).refused()
                    message shouldContain "TEST_TLS_*"
                    message shouldContain "形式が不正"
                } finally {
                    Files.delete(broken)
                }
                ServerTls.load(files(pki, serverCert).copy(cert = "/nonexistent/server.crt")).refused() shouldContain "読めません"
            }
        }

        context("設定") {
            test("環境変数の名前は接頭辞から作る。許可の一覧の既定は apisix") {
                variables.files(mapOf("TEST_TLS_CERT_FILE" to "/c", "TEST_TLS_KEY_FILE" to " ")) shouldBe
                    TlsFiles(variables, "/c", null, null)
                variables.allowedClients(emptyMap()) shouldBe setOf("apisix")
                variables.allowedClients(mapOf("TEST_TLS_ALLOWED_CLIENTS" to "apisix, other ,")) shouldBe setOf("apisix", "other")
                shouldThrow<IllegalArgumentException> { TlsVariables("bad-prefix") }
            }

            test("SAN の DNS 名を読む") {
                ClientCertificateAllowList.dnsNames(pki.issue("x", listOf("a", "b")).cert) shouldBe listOf("a", "b")
            }
        }
    })
