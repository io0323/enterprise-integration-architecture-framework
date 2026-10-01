@file:Suppress("MagicNumber") // 鍵の長さ・トークンの寿命

package io.eia.order.adapters.inbound.rest

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
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
import io.eia.platform.security.jwt.JwtVerifier
import io.eia.platform.security.jwt.JwtVerifierConfig
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.dataformat.yaml.YAMLMapper
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

internal const val ISSUER = "http://idp.test/realms/eiaf"
internal const val AUDIENCE = "order-api"

/**
 * テスト用の IdP。RSA の鍵でトークンに署名し、公開鍵(JWKS)をローカルの HTTP で配る。
 * 検証は本番と同じ JwtVerifier(JWKS を HTTP で取得する)で行う。
 */
internal class TestIdp : AutoCloseable {
    private val key: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/jwks") { exchange ->
                val body = JWKSet(key.toPublicJWK()).toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

    /** 本番と同じく、呼び出し元のクライアント(azp)がないトークンを拒否する設定。 */
    val verifier: JwtVerifier =
        JwtVerifier(
            JwtVerifierConfig(
                issuer = ISSUER,
                audience = AUDIENCE,
                jwksUri = URI.create("http://127.0.0.1:${server.address.port}/jwks"),
                requireClientId = true,
            ),
        )

    fun token(
        scopes: String = "$SCOPE_READ $SCOPE_WRITE",
        clientId: String? = "client-a",
    ): String {
        val now = Clock.System.now()
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("service-account-$clientId")
                .apply { clientId?.let { claim("azp", it) } }
                .claim("scope", scopes)
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

    override fun close() {
        verifier.close()
        server.stop(0)
    }
}

/** 契約(contracts/openapi/order-api.v1.yaml)の components.schemas で、応答の本文を検証する(P05 の P-3)。 */
internal object ContractSchemas {
    private val document: JsonNode = YAMLMapper().readTree(File("../../../contracts/openapi/order-api.v1.yaml"))
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    private val json = JsonMapper.builder().build()

    /** [schemaName] の違反(なければ空)。契約の文書の根に `$ref` を置いて、文書の中の参照を解決させる。 */
    fun violations(
        schemaName: String,
        body: String,
    ): List<String> {
        val root = (document.deepCopy() as ObjectNode).put("\$ref", "#/components/schemas/$schemaName")
        return registry.getSchema(root).validate(json.readTree(body)).map { "${it.instanceLocation}: ${it.message}" }
    }
}
