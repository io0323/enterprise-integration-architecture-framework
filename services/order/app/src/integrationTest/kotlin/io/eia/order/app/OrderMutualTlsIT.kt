@file:Suppress("MagicNumber") // 有効期間

package io.eia.order.app

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

private fun Result<OrderServer, ValidationError>.started(): OrderServer = shouldBeInstanceOf<Result.Ok<OrderServer>>().value

private fun Result<OrderServer, ValidationError>.refused(): String = shouldBeInstanceOf<Result.Err<ValidationError>>().error.message

/** API のポートの mTLS と、ヘルスチェックのポートの分け方(ADR-0024 §6。ROADMAP P05 の DoD「mTLS なしの直接接続を拒否する」)。 */
class OrderMutualTlsIT :
    FunSpec({
        val environment = AppEnvironment()
        lateinit var server: OrderServer
        beforeSpec {
            environment.start()
            val db = environment.newDatabase()
            OrderCommands.run(listOf("migrate"), environment.migrateEnv(db)) shouldBe OrderCommands.OK
            server = OrderServer.start(environment.serveEnv(db)).started()
        }
        afterSpec {
            server.stop()
            environment.close()
        }

        fun api(path: String = "/v1/orders/ord-none") = "https://localhost:${server.httpsPort}$path"

        context("API のポート(mTLS)") {
            test("ゲートウェイの証明書(SAN apisix)なら、API に届く") {
                // ない注文の 404(Problem Details)が返れば、TLS・許可の一覧・認証を通ってルートまで届いている
                val response = environment.gateway.get(api(), environment.token())
                response.statusCode() shouldBe 404
                response.body() shouldContain "https://eiaf.example/problems/not-found"
            }

            test("クライアント証明書のない接続は、TLS のハンドシェイクで拒否する") {
                shouldThrow<IOException> { TestHttp(environment.pki.anonymousClientContext()).get(api(), environment.token()) }
            }

            test("別の CA が署名した証明書は、SAN が apisix でも拒否する") {
                TestPki("Other CA").use { other ->
                    val stranger = other.issue("apisix", listOf("apisix"))
                    shouldThrow<IOException> { TestHttp(stranger.clientContext(environment.pki)).get(api(), environment.token()) }
                }
            }

            test("同じ CA の証明書でも、SAN が許可の一覧にないものは拒否する") {
                val inventory = environment.pki.issue("inventory-service", listOf("inventory-service"))
                shouldThrow<IOException> { TestHttp(inventory.clientContext(environment.pki)).get(api(), environment.token()) }
            }

            test("期限切れのクライアント証明書は拒否する") {
                val expired = environment.pki.issue("apisix", listOf("apisix"), validFrom = Clock.System.now() - 40.days)
                shouldThrow<IOException> { TestHttp(expired.clientContext(environment.pki)).get(api(), environment.token()) }
            }

            test("平文の HTTP で API のポートに接続しても、応答しない") {
                val plaintext = "http://127.0.0.1:${server.httpsPort}/v1/orders/ord-none"
                shouldThrow<IOException> { environment.plain.get(plaintext, environment.token()) }
            }

            test("許可の一覧は ORDER_TLS_ALLOWED_CLIENTS で変えられる") {
                val db = environment.newDatabase()
                OrderCommands.run(listOf("migrate"), environment.migrateEnv(db)) shouldBe OrderCommands.OK
                val other = OrderServer.start(environment.serveEnv(db, mapOf("ORDER_TLS_ALLOWED_CLIENTS" to "inventory-service"))).started()
                try {
                    val inventory = environment.pki.issue("inventory-service", listOf("inventory-service"))
                    val url = "https://localhost:${other.httpsPort}/v1/orders/ord-none"
                    TestHttp(inventory.clientContext(environment.pki)).get(url, environment.token()).statusCode() shouldBe 404
                    shouldThrow<IOException> { environment.gateway.get(url, environment.token()) }
                } finally {
                    other.stop()
                }
            }
        }

        context("ヘルスチェックのポート(平文)") {
            test("ヘルスチェックは平文のポートだけで返し、API は平文のポートにない") {
                environment.plain.get("http://127.0.0.1:${server.healthPort}/health/live").statusCode() shouldBe 200
                environment.plain.get("http://127.0.0.1:${server.healthPort}/health/ready").statusCode() shouldBe 200
                environment.plain
                    .get("http://127.0.0.1:${server.healthPort}/v1/orders/ord-none", environment.token())
                    .statusCode() shouldBe 404
                environment.gateway.get(api("/health/live")).statusCode() shouldBe 404
            }
        }

        context("起動時の証明書の確認") {
            test("サーバ証明書が期限切れなら起動せず、原因と直し方(make certs)を示す") {
                val expired = environment.pki.issue("order-service", listOf("localhost"), validFrom = Clock.System.now() - 40.days)
                val message = OrderServer.start(environment.serveEnv("unused") + environment.tlsEnv(expired)).refused()
                message shouldContain TlsFiles.CERT
                message shouldContain "有効期限が切れています"
                message shouldContain "make certs"
            }

            test("クライアント証明書を検証する CA が期限切れなら起動しない") {
                TestPki("Expired CA", caValidFrom = Clock.System.now() - 40.days).use { expired ->
                    val message =
                        OrderServer
                            .start(environment.serveEnv("unused", mapOf(TlsFiles.CLIENT_CA to expired.caFile.toString())))
                            .refused()
                    message shouldContain TlsFiles.CLIENT_CA
                    message shouldContain "有効期限が切れています"
                }
            }

            test("TLS のファイルの設定がなければ起動しない") {
                val env = environment.serveEnv("unused") - setOf(TlsFiles.CERT, TlsFiles.KEY, TlsFiles.CLIENT_CA)
                val message = OrderServer.start(env).refused()
                listOf(TlsFiles.CERT, TlsFiles.KEY, TlsFiles.CLIENT_CA).forEach { message shouldContain it }
            }

            test("鍵が PKCS#8 の PEM でなければ起動せず、直し方を示す") {
                val broken = Files.createTempFile("broken", ".key").also { Files.writeString(it, "not a key") }
                try {
                    val message = OrderServer.start(environment.serveEnv("unused", mapOf(TlsFiles.KEY to broken.toString()))).refused()
                    message shouldContain "形式が不正"
                    message shouldContain "make certs"
                } finally {
                    Files.delete(broken)
                }
            }
        }
    })
