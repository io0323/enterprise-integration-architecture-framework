package io.eia.order.app

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val REQUIRED = mapOf("ORDER_DB_URL" to "jdbc:postgresql://localhost:19432/order_service")

class OrderConfigSpec :
    FunSpec({
        test("既定値: ロール・ポート・許可するクライアント・予算 10 秒・リース 60 秒・削除の間隔 5 分(ADR-0024)") {
            val config = OrderConfig.fromEnvironment(REQUIRED).shouldBeInstanceOf<Result.Ok<OrderConfig>>().value
            config.ownerUser shouldBe "order_service"
            config.appUser shouldBe "order_service_app"
            config.httpsPort shouldBe 8443
            config.healthPort shouldBe 8081
            config.allowedClients shouldBe setOf("apisix")
            config.tls shouldBe TlsFiles(null, null, null)
            config.audience shouldBe "order-api"
            config.requestBudget shouldBe 10.seconds
            config.idempotencyLease shouldBe 60.seconds
            config.purgeInterval shouldBe 5.minutes
        }

        test("期間は Kotlin の表記と ISO 8601 の両方を読む") {
            val config =
                OrderConfig
                    .fromEnvironment(REQUIRED + mapOf("ORDER_REQUEST_BUDGET" to "PT3S", "ORDER_IDEMPOTENCY_PURGE_INTERVAL" to "30s"))
                    .shouldBeInstanceOf<Result.Ok<OrderConfig>>()
                    .value
            config.requestBudget shouldBe 3.seconds
            config.purgeInterval shouldBe 30.seconds
        }

        test("必須の値がない・期間が不正・ポートが範囲外なら、違反をまとめて返す") {
            val error =
                OrderConfig
                    .fromEnvironment(mapOf("ORDER_REQUEST_BUDGET" to "soon", "ORDER_HTTPS_PORT" to "70000"))
                    .shouldBeInstanceOf<Result.Err<ValidationError>>()
                    .error
            error.violations.map { it.field }.toSet() shouldBe setOf("ORDER_DB_URL", "ORDER_REQUEST_BUDGET", "ORDER_HTTPS_PORT")
        }

        test("リクエストの予算は、冪等のリースより短くする(ADR-0024 §3)") {
            val error =
                OrderConfig
                    .fromEnvironment(REQUIRED + mapOf("ORDER_REQUEST_BUDGET" to "60s", "ORDER_IDEMPOTENCY_LEASE" to "60s"))
                    .shouldBeInstanceOf<Result.Err<ValidationError>>()
                    .error
            error.violations.single().field shouldBe "ORDER_REQUEST_BUDGET"
        }

        test("許可するクライアントはカンマ区切りで、空白と空の要素を無視する。1 つもなければ違反") {
            val config =
                OrderConfig
                    .fromEnvironment(REQUIRED + mapOf("ORDER_TLS_ALLOWED_CLIENTS" to " apisix, gateway-b ,,"))
                    .shouldBeInstanceOf<Result.Ok<OrderConfig>>()
                    .value
            config.allowedClients shouldBe setOf("apisix", "gateway-b")
            OrderConfig
                .fromEnvironment(REQUIRED + mapOf("ORDER_TLS_ALLOWED_CLIENTS" to " , "))
                .shouldBeInstanceOf<Result.Err<ValidationError>>()
                .error.violations
                .single()
                .field shouldBe "ORDER_TLS_ALLOWED_CLIENTS"
        }

        test("API とヘルスチェックは別のポートにする(0 は空いているポートの割り当てなので、どちらも 0 でよい)") {
            OrderConfig
                .fromEnvironment(REQUIRED + mapOf("ORDER_HTTPS_PORT" to "9000", "ORDER_HEALTH_PORT" to "9000"))
                .shouldBeInstanceOf<Result.Err<ValidationError>>()
                .error.violations
                .single()
                .field shouldBe "ORDER_HEALTH_PORT"
            OrderConfig
                .fromEnvironment(REQUIRED + mapOf("ORDER_HTTPS_PORT" to "0", "ORDER_HEALTH_PORT" to "0"))
                .shouldBeInstanceOf<Result.Ok<OrderConfig>>()
        }

        test("TLS のファイルのパスを読む(serve で必須かどうかの確認は ServerTls が行う)") {
            val config =
                OrderConfig
                    .fromEnvironment(
                        REQUIRED +
                            mapOf(
                                "ORDER_TLS_CERT_FILE" to "/certs/order-service.crt",
                                "ORDER_TLS_KEY_FILE" to "/certs/order-service.key",
                                "ORDER_TLS_CLIENT_CA_FILE" to "/certs/ca.crt",
                            ),
                    ).shouldBeInstanceOf<Result.Ok<OrderConfig>>()
                    .value
            config.tls shouldBe TlsFiles("/certs/order-service.crt", "/certs/order-service.key", "/certs/ca.crt")
        }
    })
