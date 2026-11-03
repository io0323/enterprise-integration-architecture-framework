@file:Suppress("MagicNumber") // テストの固定値

package io.eia.bffgraphql.app

import com.google.protobuf.Timestamp
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.eia.platform.grpc.GrpcServer
import io.eia.platform.grpc.GrpcServerConfig
import io.eia.platform.grpc.GrpcService
import io.eia.platform.grpc.GrpcTelemetry
import io.eia.platform.grpcstubs.inventory.v1.BatchGetStockRequest
import io.eia.platform.grpcstubs.inventory.v1.BatchGetStockResponse
import io.eia.platform.grpcstubs.inventory.v1.StockLevel
import io.eia.platform.grpcstubs.inventory.v1.StockQueryServiceGrpcKt
import io.eia.platform.grpcstubs.shipping.v1.BatchGetShipmentsRequest
import io.eia.platform.grpcstubs.shipping.v1.BatchGetShipmentsResponse
import io.eia.platform.grpcstubs.shipping.v1.Shipment
import io.eia.platform.grpcstubs.shipping.v1.ShipmentQueryServiceGrpcKt
import io.eia.platform.grpcstubs.shipping.v1.ShipmentStatus
import io.eia.platform.security.jwt.VerifiedToken
import io.eia.platform.security.tls.ServerTls
import io.eia.platform.security.tls.TlsFiles
import io.eia.platform.security.tls.TlsVariables
import io.eia.platform.testsupport.TestPki
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.getOrNull
import io.eia.shared.kernel.ok
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.opentelemetry.api.OpenTelemetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Collections
import java.util.Date
import javax.net.ssl.SSLContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val ISSUER = "http://issuer.test/realms/eiaf"
private const val ORDER_JSON =
    """{"id":"order-1","customerId":"customer-1","status":"PLACED","orderedAt":"2026-11-03T00:00:00Z",
    "lines":[{"lineNumber":1,"productId":"product-1","sku":"SKU-1","quantity":2,
      "unitPrice":{"amount":"10.00","currency":"USD"},"lineAmount":{"amount":"20.00","currency":"USD"}}],
    "totalAmount":{"amount":"20.00","currency":"USD"},
    "shippingAddress":{"countryCode":"JP","postalCode":"100-0001","city":"Tokyo","line1":"1-1"}}"""
private const val FULL_QUERY =
    """{"query":"{ order(id: \"order-1\") { id status lines { sku stock { available } } shipment { status } } }"}"""

private fun HttpExchange.respondJson(
    body: String,
    status: Int = 200,
) {
    responseHeaders.add("Content-Type", "application/json")
    val bytes = body.toByteArray()
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

/** 起動から応答までの全体: 実際の mTLS・JWT の検証・Client Credentials・gRPC(下流だけ偽物。ADR-0043)。 */
class BffAppIT :
    FunSpec({
        val pki = TestPki()
        val serverCert = pki.issue("bff-graphql", listOf("localhost", "bff-graphql"))
        val gatewayCert = pki.issue("apisix", listOf("apisix"))
        val strangerCert = pki.issue("stranger", listOf("stranger"))
        val upstreamCert = pki.issue("upstream", listOf("localhost"))
        val tokenKey: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()
        val telemetry = OpenTelemetry.noop().let { GrpcTelemetry(it, it.getTracer("test"), it.getMeter("test")) }
        val requestedScopes = Collections.synchronizedList(mutableListOf<String>())
        val orderCalls = Collections.synchronizedList(mutableListOf<String>())

        val fakes =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/jwks") { it.respondJson(JWKSet(tokenKey.toPublicJWK()).toString()) }
                createContext("/token") { exchange ->
                    requestedScopes += exchange.requestBody.readBytes().decodeToString()
                    exchange.respondJson("""{"access_token":"downstream","token_type":"Bearer","expires_in":300}""")
                }
                createContext("/sales/v1/orders") { exchange ->
                    orderCalls += exchange.requestURI.query
                    exchange.responseHeaders.add("X-RateLimit-Limit", "600")
                    exchange.respondJson("""{"orders":[$ORDER_JSON],"notFound":[]}""")
                }
                start()
            }

        fun tls(cert: TestPki.Issued): ServerTls =
            checkNotNull(
                ServerTls
                    .load(TlsFiles(TlsVariables("TEST"), cert.certFile.toString(), cert.keyFile.toString(), pki.caFile.toString()))
                    .getOrNull(),
            )

        val upstreams =
            GrpcServer.start(
                GrpcServerConfig(
                    port = 0,
                    tls = tls(upstreamCert),
                    allowedClients = setOf("bff-graphql"),
                    verify = {
                        ok(
                            VerifiedToken(
                                "iss",
                                "sub",
                                "eiaf-bff-graphql",
                                listOf("aud"),
                                setOf("inventory.stock:read", "shipping.shipment:read"),
                                Clock.System.now(),
                                Clock.System.now() + 1.hours,
                                null,
                            ),
                        )
                    },
                    telemetry = telemetry,
                    services =
                        listOf(
                            GrpcService(
                                object : StockQueryServiceGrpcKt.StockQueryServiceCoroutineImplBase() {
                                    override suspend fun batchGetStock(request: BatchGetStockRequest) =
                                        BatchGetStockResponse
                                            .newBuilder()
                                            .addLevels(
                                                StockLevel
                                                    .newBuilder()
                                                    .setSku("SKU-1")
                                                    .setAvailable(7)
                                                    .setUpdatedAt(Timestamp.newBuilder().setSeconds(1_800_000_000)),
                                            ).build()
                                },
                                setOf("inventory.stock:read"),
                            ),
                            GrpcService(
                                object : ShipmentQueryServiceGrpcKt.ShipmentQueryServiceCoroutineImplBase() {
                                    override suspend fun batchGetShipments(request: BatchGetShipmentsRequest) =
                                        BatchGetShipmentsResponse
                                            .newBuilder()
                                            .addShipments(
                                                Shipment
                                                    .newBuilder()
                                                    .setOrderId("order-1")
                                                    .setStatus(ShipmentStatus.SHIPMENT_STATUS_SHIPPED),
                                            ).build()
                                },
                                setOf("shipping.shipment:read"),
                            ),
                        ),
                ),
            )

        val fakesUrl = "http://127.0.0.1:${fakes.address.port}"
        val env =
            mapOf(
                "BFF_HTTPS_PORT" to "0",
                "BFF_HEALTH_PORT" to "0",
                "BFF_TLS_CERT_FILE" to serverCert.certFile.toString(),
                "BFF_TLS_KEY_FILE" to serverCert.keyFile.toString(),
                "BFF_TLS_CLIENT_CA_FILE" to pki.caFile.toString(),
                "OIDC_ISSUER" to ISSUER,
                "OIDC_JWKS_URI" to "$fakesUrl/jwks",
                "BFF_TOKEN_URL" to "$fakesUrl/token",
                "BFF_CLIENT_SECRET" to "test-secret",
                "BFF_GATEWAY_URL" to fakesUrl,
                "BFF_INVENTORY_TARGET" to "localhost:${upstreams.port}",
                "BFF_SHIPPING_TARGET" to "localhost:${upstreams.port}",
            )
        val server = BffServer.start(env).shouldBeInstanceOf<Result.Ok<BffServer>>().value
        val httpsUrl = "https://localhost:${server.port(BffServer.HTTPS_CONNECTOR)}/v1/graphql"
        val healthUrl = "http://127.0.0.1:${server.port(BffServer.HEALTH_CONNECTOR)}"

        afterSpec {
            server.stop()
            upstreams.stop()
            fakes.stop(0)
            pki.close()
        }

        fun token(
            audience: String = "bff-graphql",
            scope: String = "sales.order:read inventory.stock:read shipping.shipment:read",
            azp: String? = "eiaf-e2e",
        ): String {
            val now = Clock.System.now()
            val claims =
                JWTClaimsSet
                    .Builder()
                    .issuer(ISSUER)
                    .audience(audience)
                    .subject("caller")
                    .claim("scope", scope)
                    .apply { azp?.let { claim("azp", it) } }
                    .issueTime(Date(now.toEpochMilliseconds()))
                    .expirationTime(Date((now + 5.minutes).toEpochMilliseconds()))
                    .build()
            val header =
                JWSHeader
                    .Builder(JWSAlgorithm.RS256)
                    .keyID(tokenKey.keyID)
                    .type(JOSEObjectType.JWT)
                    .build()
            return SignedJWT(header, claims).apply { sign(RSASSASigner(tokenKey)) }.serialize()
        }

        fun post(
            context: SSLContext,
            bearer: String?,
            url: String = httpsUrl,
        ): HttpResponse<String> {
            val client =
                HttpClient
                    .newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .sslContext(context)
                    .build()
            val request =
                HttpRequest
                    .newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .apply { bearer?.let { header("Authorization", "Bearer $it") } }
                    .POST(HttpRequest.BodyPublishers.ofString(FULL_QUERY))
                    .build()
            return client.send(request, HttpResponse.BodyHandlers.ofString())
        }

        val gateway = gatewayCert.clientContext(pki)

        test("JWKS を取得すると ready になる。平文のポートに GraphQL はない") {
            eventually(30.seconds) {
                val http = HttpClient.newHttpClient()
                val ready = HttpRequest.newBuilder(URI.create("$healthUrl/health/ready")).build()
                http.send(ready, HttpResponse.BodyHandlers.ofString()).statusCode() shouldBe 200
            }
            post(gateway, token(), "$healthUrl/v1/graphql").statusCode() shouldBe 404
        }

        test("Gateway の証明書で、注文 + 在庫 + 出荷を 1 回で返す。下流のトークンは下流ごとに必要なスコープだけ") {
            orderCalls.clear()
            val response = post(gateway, token())

            response.statusCode() shouldBe 200
            val body = Json.parseToJsonElement(response.body()).jsonObject
            body["errors"] shouldBe null
            val order =
                body
                    .getValue("data")
                    .jsonObject
                    .getValue("order")
                    .jsonObject
            order.getValue("status").jsonPrimitive.content shouldBe "PLACED"
            order
                .getValue("shipment")
                .jsonObject
                .getValue("status")
                .jsonPrimitive.content shouldBe "SHIPPED"
            orderCalls.size shouldBe 1
            requestedScopes.toSet().map { it.substringAfter("scope=").substringBefore('&').replace("%3A", ":") }.toSet() shouldBe
                setOf("sales.order:read", "inventory.stock:read", "shipping.shipment:read")
        }

        test("トークンがない・aud が違う・azp がないトークンは 401(mTLS の Gateway からでも)") {
            post(gateway, null).statusCode() shouldBe 401
            post(gateway, token(audience = "order-api")).statusCode() shouldBe 401
            post(gateway, token(azp = null)).statusCode() shouldBe 401
        }

        test("Gateway 以外の証明書・クライアント証明書なしは接続を拒否する(DoD: mTLS でない接続の拒否)") {
            shouldThrowAny { post(strangerCert.clientContext(pki), token()) }
            shouldThrowAny { post(pki.anonymousClientContext(), token()) }
        }
    })
