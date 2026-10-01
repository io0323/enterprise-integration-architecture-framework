@file:Suppress("MagicNumber") // HTTP の状態コード・待ち時間・件数

package io.eia.tests.e2e

import io.eia.tests.e2e.E2eEnvironment.Client
import io.eia.tests.e2e.E2eEnvironment.ORDERS
import io.eia.tests.e2e.E2eEnvironment.ORDER_SERVICE_TLS
import io.eia.tests.e2e.E2eEnvironment.TOO_MANY_REQUESTS
import io.eia.tests.e2e.E2eEnvironment.certs
import io.eia.tests.e2e.E2eEnvironment.client
import io.eia.tests.e2e.E2eEnvironment.json
import io.eia.tests.e2e.E2eEnvironment.request
import io.eia.tests.e2e.E2eEnvironment.send
import io.eia.tests.e2e.E2eEnvironment.sendRespectingRateLimit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Rate Limit の枠(60 件 / 60 秒)を確実に超える件数。 */
private const val MAX_REQUESTS = 80

/** 開発用 CA を信頼し、[clientCert] があればクライアント証明書として出す [SSLContext]。 */
private fun sslContext(clientCert: String? = null): SSLContext {
    val factory = CertificateFactory.getInstance("X.509")

    fun certificate(name: String) = Files.newInputStream(certs.resolve(name)).use { factory.generateCertificate(it) as X509Certificate }
    val trust =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(
                KeyStore.getInstance("PKCS12").apply {
                    load(null, null)
                    setCertificateEntry("ca", certificate("ca.crt"))
                },
            )
        }
    val keyManagers =
        clientCert?.let { name ->
            val password = "e2e".toCharArray()
            val keyStore =
                KeyStore.getInstance("PKCS12").apply {
                    load(null, null)
                    setKeyEntry(name, privateKey(certs.resolve("$name.key")), password, arrayOf(certificate("$name.crt")))
                }
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, password) }.keyManagers
        }
    return SSLContext.getInstance("TLS").apply { init(keyManagers, trust.trustManagers, SecureRandom()) }
}

/** gen-dev-certs.sh の鍵(EC P-256 の PKCS#8 の PEM)。 */
private fun privateKey(file: Path) =
    KeyFactory.getInstance("EC").generatePrivate(
        PKCS8EncodedKeySpec(
            Base64.getMimeDecoder().decode(
                Files.readString(file).substringAfter("-----BEGIN PRIVATE KEY-----").substringBefore("-----END PRIVATE KEY-----"),
            ),
        ),
    )

class GatewayE2E :
    FunSpec({
        context("クライアント(azp)ごとの Rate Limit(ROADMAP P05 の DoD「429 に Retry-After が付く」。ADR-0023 §5)") {
            test("枠を超えたクライアントは 429 + Retry-After(整数の秒)+ Problem Details。ほかのクライアントは影響を受けない") {
                // 枠を使い切るのは B だけにする(ほかのシナリオは A で送る)
                var limited: HttpResponse<String>? = null
                repeat(MAX_REQUESTS) {
                    if (limited == null) {
                        val response = send(request("$ORDERS/ord-none", Client.B).GET())
                        if (response.statusCode() == TOO_MANY_REQUESTS) limited = response
                    }
                }
                val response = limited.shouldNotBeNull()
                (response.header("Retry-After")?.toIntOrNull() ?: 0) shouldBeGreaterThan 0
                response.header("Content-Type").shouldNotBeNull() shouldStartWith "application/problem+json"
                json(response.body())
                    .jsonObject
                    .getValue("type")
                    .jsonPrimitive.content shouldBe
                    "https://eiaf.example/problems/rate-limited"

                sendRespectingRateLimit(request("$ORDERS/ord-none", Client.A).GET()).statusCode() shouldNotBe TOO_MANY_REQUESTS
            }
        }

        context("mTLS なしの直接接続を拒否する(ROADMAP P05 の DoD。ADR-0024 §6)") {
            val url = "$ORDER_SERVICE_TLS/v1/orders/ord-none"

            test("クライアント証明書のない接続は、TLS のハンドシェイクで拒否される") {
                shouldThrow<IOException> { send(request(url).GET(), on = client(sslContext())) }
            }

            test("同じ CA の証明書でも、許可されていない SAN(order-service 自身の証明書)は拒否される") {
                shouldThrow<IOException> { send(request(url).GET(), on = client(sslContext(clientCert = "order-service"))) }
            }

            test("ゲートウェイの証明書(SAN apisix)なら届く(拒否の理由が mTLS であることの対照)") {
                send(request(url).GET(), on = client(sslContext(clientCert = "apisix"))).statusCode() shouldBe 404
            }
        }
    })
