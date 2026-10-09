package io.eia.payment.app

import io.eia.payment.domain.Amount
import io.eia.payment.domain.SettledRetention
import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private val BASE = mapOf("PAYMENT_DB_URL" to "jdbc:postgresql://postgres:5432/payment_service")

class PaymentConfigSpec :
    FunSpec({
        test("既定値: ロール名・ポート 8081・保持期間 30 日・削除の間隔 1 時間。serve には Kafka と Apicurio が必須") {
            val config = PaymentConfig.fromEnvironment(BASE).ok()
            config.ownerUser shouldBe "payment_service"
            config.appUser shouldBe "payment_service_app"
            config.cdcUser shouldBe "debezium"
            config.healthPort shouldBe 8081
            config.retention shouldBe SettledRetention.DEFAULT
            config.purgeInterval shouldBe 1.hours
            (config.forServe() as Result.Err).error.violations.map { it.field } shouldBe
                listOf(PaymentConfig.KAFKA_BOOTSTRAP, PaymentConfig.SCHEMA_REGISTRY_URL)
        }

        test("値を読む(期間は ISO 8601 と Kotlin の表記)") {
            val config =
                PaymentConfig
                    .fromEnvironment(
                        BASE +
                            mapOf(
                                "PAYMENT_KAFKA_BOOTSTRAP" to "kafka:9092",
                                "PAYMENT_SCHEMA_REGISTRY_URL" to "http://apicurio:8080/apis/registry/v3",
                                "PAYMENT_HEALTH_PORT" to "0",
                                "PAYMENT_SETTLED_RETENTION" to "P45D",
                                "PAYMENT_PURGE_INTERVAL" to "10m",
                            ),
                    ).ok()
            config.healthPort shouldBe 0
            config.retention.value shouldBe 45.days
            config.purgeInterval shouldBe 10.minutes
            config.forServe().ok() shouldBe ServeSettings("kafka:9092", "http://apicurio:8080/apis/registry/v3")
        }

        test("誤り: DB の URL がない・ポートの範囲外・期間の形式・保持期間が 14 日より短い(ADR-0029 §5)") {
            val error =
                (
                    PaymentConfig.fromEnvironment(
                        mapOf(
                            "PAYMENT_HEALTH_PORT" to "70000",
                            "PAYMENT_SETTLED_RETENTION" to "13d",
                            "PAYMENT_PURGE_INTERVAL" to "soon",
                        ),
                    ) as Result.Err
                ).error
            error.violations.map { it.field }.toSet() shouldBe
                setOf("PAYMENT_DB_URL", "PAYMENT_HEALTH_PORT", "PAYMENT_SETTLED_RETENTION", "PAYMENT_PURGE_INTERVAL")
        }

        test("コマンド: 知らないサブコマンドと、serve の環境の所有者のパスワードは使い方の誤り(2)") {
            PaymentCommands.run(listOf("unknown"), BASE) shouldBe PaymentCommands.USAGE
            PaymentCommands.run(listOf("serve"), BASE + ("PAYMENT_DB_PASSWORD" to "x")) shouldBe PaymentCommands.USAGE
            PaymentCommands.run(listOf("serve"), BASE + ("PAYMENT_DB_PASSWORD_FILE" to "/run/x")) shouldBe PaymentCommands.USAGE
            // 設定の誤り(Kafka がない)も 2
            PaymentCommands.run(listOf("serve"), BASE + ("PAYMENT_APP_DB_PASSWORD" to "x")) shouldBe PaymentCommands.USAGE
            // migrate の所有者のパスワードがない
            PaymentCommands.run(listOf("migrate"), BASE) shouldBe PaymentCommands.USAGE
        }

        test("模擬の承認の上限: 既定は 1,000,000 JPY。最小通貨単位と通貨を読み、誤りは拒否する(ADR-0029 §7)") {
            PaymentConfig.fromEnvironment(BASE).ok().limit shouldBe Amount(1_000_000, "JPY")
            val usd = BASE + mapOf("PAYMENT_AUTHORIZATION_LIMIT" to "5000", "PAYMENT_AUTHORIZATION_CURRENCY" to "USD")
            PaymentConfig.fromEnvironment(usd).ok().limit shouldBe Amount(5000, "USD")
            val notNumber = PaymentConfig.fromEnvironment(BASE + ("PAYMENT_AUTHORIZATION_LIMIT" to "many")) as Result.Err
            notNumber.error.violations.map { it.field } shouldBe listOf("PAYMENT_AUTHORIZATION_LIMIT", "amount.minorUnits")
            (PaymentConfig.fromEnvironment(BASE + ("PAYMENT_AUTHORIZATION_CURRENCY" to "yen")) is Result.Err) shouldBe true
        }
    })
