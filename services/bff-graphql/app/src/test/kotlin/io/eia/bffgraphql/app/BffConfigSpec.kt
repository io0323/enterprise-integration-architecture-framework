package io.eia.bffgraphql.app

import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.URI
import kotlin.time.Duration.Companion.seconds

private val REQUIRED =
    mapOf(
        "OIDC_ISSUER" to "http://localhost:19180/realms/eiaf",
        "OIDC_JWKS_URI" to "http://keycloak:8080/certs",
        "BFF_TOKEN_URL" to "http://keycloak:8080/token",
        "BFF_GATEWAY_URL" to "http://apisix:9080",
        "BFF_INVENTORY_TARGET" to "inventory-service:9443",
        "BFF_SHIPPING_TARGET" to "shipping-service:9443",
    )

private fun config(env: Map<String, String>): BffConfig = BffConfig.fromEnvironment(env).shouldBeInstanceOf<Result.Ok<BffConfig>>().value

private fun violations(env: Map<String, String>): List<String> =
    BffConfig
        .fromEnvironment(env)
        .shouldBeInstanceOf<Result.Err<io.eia.shared.kernel.ValidationError>>()
        .error.violations
        .map { it.field }

class BffConfigSpec :
    FunSpec({
        test("既定値: イントロスペクションは無効。予算 5 秒。受け入れるのは Gateway の証明書だけ。読み取りだけのクライアント") {
            val config = config(REQUIRED)
            config.introspectionEnabled shouldBe false
            config.requestBudget shouldBe 5.seconds
            config.allowedClients shouldBe setOf("apisix")
            config.clientId shouldBe "eiaf-bff-graphql"
            config.audience shouldBe "bff-graphql"
            config.httpsPort shouldBe 8443
            config.healthPort shouldBe 8081
            config.complexityCapacity shouldBe 1000
            config.complexityRefillPerSecond shouldBe 60
            config.gatewayUrl shouldBe URI("http://apisix:9080")
        }

        test("必須の値がなければ、まとめて知らせる") {
            violations(emptyMap()) shouldContainAll REQUIRED.keys
        }

        test("誤った値は、設定の誤りにする(黙って既定にしない。特にイントロスペクション)") {
            violations(REQUIRED + (BffConfig.INTROSPECTION_ENABLED to "yes")) shouldBe listOf(BffConfig.INTROSPECTION_ENABLED)
            violations(REQUIRED + (BffConfig.COMPLEXITY_CAPACITY to "0")) shouldBe listOf(BffConfig.COMPLEXITY_CAPACITY)
            violations(REQUIRED + (BffConfig.COMPLEXITY_REFILL to "x")) shouldBe listOf(BffConfig.COMPLEXITY_REFILL)
            violations(REQUIRED + ("BFF_REQUEST_BUDGET" to "-1s")) shouldBe listOf("BFF_REQUEST_BUDGET")
            violations(REQUIRED + ("BFF_HTTPS_PORT" to "70000")) shouldBe listOf("BFF_HTTPS_PORT")
        }

        test("イントロスペクションは true を明示したときだけ有効") {
            config(REQUIRED + (BffConfig.INTROSPECTION_ENABLED to "true")).introspectionEnabled shouldBe true
        }
    })
