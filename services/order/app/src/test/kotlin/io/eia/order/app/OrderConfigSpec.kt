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
        test("既定値: ロール・ポート・予算 10 秒・リース 60 秒・削除の間隔 5 分(ADR-0024)") {
            val config = OrderConfig.fromEnvironment(REQUIRED).shouldBeInstanceOf<Result.Ok<OrderConfig>>().value
            config.ownerUser shouldBe "order_service"
            config.appUser shouldBe "order_service_app"
            config.httpPort shouldBe 8080
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
                    .fromEnvironment(mapOf("ORDER_REQUEST_BUDGET" to "soon", "ORDER_HTTP_PORT" to "70000"))
                    .shouldBeInstanceOf<Result.Err<ValidationError>>()
                    .error
            error.violations.map { it.field }.toSet() shouldBe setOf("ORDER_DB_URL", "ORDER_REQUEST_BUDGET", "ORDER_HTTP_PORT")
        }

        test("リクエストの予算は、冪等のリースより短くする(ADR-0024 §3)") {
            val error =
                OrderConfig
                    .fromEnvironment(REQUIRED + mapOf("ORDER_REQUEST_BUDGET" to "60s", "ORDER_IDEMPOTENCY_LEASE" to "60s"))
                    .shouldBeInstanceOf<Result.Err<ValidationError>>()
                    .error
            error.violations.single().field shouldBe "ORDER_REQUEST_BUDGET"
        }
    })
