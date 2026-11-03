package io.eia.shipping.app

import io.eia.shared.kernel.Result
import io.eia.shipping.domain.SettledRetention
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private val BASE = mapOf("SHIPPING_DB_URL" to "jdbc:postgresql://postgres:5432/shipping_service")

class ShippingConfigSpec :
    FunSpec({
        test("既定値: ロール名・ポート 8081・保持期間 30 日・削除の間隔 1 時間。serve には Kafka と Apicurio が必須") {
            val config = ShippingConfig.fromEnvironment(BASE).ok()
            config.ownerUser shouldBe "shipping_service"
            config.appUser shouldBe "shipping_service_app"
            config.cdcUser shouldBe "debezium"
            config.healthPort shouldBe 8081
            config.retention shouldBe SettledRetention.DEFAULT
            config.purgeInterval shouldBe 1.hours
            (config.forServe() as Result.Err).error.violations.map { it.field } shouldBe
                listOf(
                    ShippingConfig.KAFKA_BOOTSTRAP,
                    ShippingConfig.SCHEMA_REGISTRY_URL,
                    ShippingConfig.GRPC_TLS.allowedClients,
                    ShippingConfig.OIDC_ISSUER,
                    ShippingConfig.OIDC_JWKS_URI,
                )
            config.grpc.port shouldBe 9443
            config.grpc.audience shouldBe "shipping-grpc"
            config.grpc.maxBudget shouldBe 3.seconds
        }

        test("値を読む(期間は ISO 8601 と Kotlin の表記)") {
            val config =
                ShippingConfig
                    .fromEnvironment(
                        BASE +
                            mapOf(
                                "SHIPPING_KAFKA_BOOTSTRAP" to "kafka:9092",
                                "SHIPPING_SCHEMA_REGISTRY_URL" to "http://apicurio:8080/apis/registry/v3",
                                "SHIPPING_HEALTH_PORT" to "0",
                                "SHIPPING_SETTLED_RETENTION" to "P45D",
                                "SHIPPING_PURGE_INTERVAL" to "10m",
                                "SHIPPING_GRPC_PORT" to "0",
                                "SHIPPING_GRPC_TLS_ALLOWED_CLIENTS" to "bff-graphql, other",
                                "SHIPPING_GRPC_MAX_BUDGET" to "500ms",
                                "OIDC_ISSUER" to "http://issuer",
                                "OIDC_JWKS_URI" to "http://issuer/jwks",
                            ),
                    ).ok()
            config.healthPort shouldBe 0
            config.retention.value shouldBe 45.days
            config.purgeInterval shouldBe 10.minutes
            config.grpc.port shouldBe 0
            config.grpc.allowedClients shouldBe setOf("bff-graphql", "other")
            config.grpc.maxBudget shouldBe 500.milliseconds
            config.forServe().ok() shouldBe ServeSettings("kafka:9092", "http://apicurio:8080/apis/registry/v3")
        }

        test("誤り: DB の URL がない・ポートの範囲外・期間の形式・保持期間が 14 日より短い(ADR-0029 §5)") {
            val error =
                (
                    ShippingConfig.fromEnvironment(
                        mapOf(
                            "SHIPPING_HEALTH_PORT" to "70000",
                            "SHIPPING_SETTLED_RETENTION" to "13d",
                            "SHIPPING_PURGE_INTERVAL" to "soon",
                        ),
                    ) as Result.Err
                ).error
            error.violations.map { it.field }.toSet() shouldBe
                setOf("SHIPPING_DB_URL", "SHIPPING_HEALTH_PORT", "SHIPPING_SETTLED_RETENTION", "SHIPPING_PURGE_INTERVAL")
        }

        test("コマンド: 知らないサブコマンドと、serve の環境の所有者のパスワードは使い方の誤り(2)") {
            ShippingCommands.run(listOf("unknown"), BASE) shouldBe ShippingCommands.USAGE
            ShippingCommands.run(listOf("serve"), BASE + ("SHIPPING_DB_PASSWORD" to "x")) shouldBe ShippingCommands.USAGE
            ShippingCommands.run(listOf("serve"), BASE + ("SHIPPING_DB_PASSWORD_FILE" to "/run/x")) shouldBe ShippingCommands.USAGE
            // 設定の誤り(Kafka がない)も 2
            ShippingCommands.run(listOf("serve"), BASE + ("SHIPPING_APP_DB_PASSWORD" to "x")) shouldBe ShippingCommands.USAGE
            // migrate の所有者のパスワードがない
            ShippingCommands.run(listOf("migrate"), BASE) shouldBe ShippingCommands.USAGE
        }

        test("模擬の出荷できる国: 既定は JP。カンマ区切りを読み、誤りは拒否する(ADR-0029 §7)") {
            ShippingConfig.fromEnvironment(BASE).ok().supportedCountries shouldBe setOf("JP")
            val two = BASE + ("SHIPPING_SUPPORTED_COUNTRIES" to "JP, US")
            ShippingConfig.fromEnvironment(two).ok().supportedCountries shouldBe setOf("JP", "US")
            listOf("jp", " , ", "JPN").forEach { value ->
                val error = (ShippingConfig.fromEnvironment(BASE + ("SHIPPING_SUPPORTED_COUNTRIES" to value)) as Result.Err).error
                error.violations.single().field shouldBe "SHIPPING_SUPPORTED_COUNTRIES"
            }
        }
    })
