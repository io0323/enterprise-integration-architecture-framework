package io.eia.inventory.app

import io.eia.inventory.domain.SettledRetention
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

private val BASE = mapOf("INVENTORY_DB_URL" to "jdbc:postgresql://postgres:5432/inventory_service")

class InventoryConfigSpec :
    FunSpec({
        test("既定値: ロール名・ポート 8081・保持期間 30 日・削除の間隔 1 時間。serve には Kafka と Apicurio が必須") {
            val config = InventoryConfig.fromEnvironment(BASE).ok()
            config.ownerUser shouldBe "inventory_service"
            config.appUser shouldBe "inventory_service_app"
            config.cdcUser shouldBe "debezium"
            config.healthPort shouldBe 8081
            config.retention shouldBe SettledRetention.DEFAULT
            config.purgeInterval shouldBe 1.hours
            (config.forServe() as Result.Err).error.violations.map { it.field } shouldBe
                listOf(InventoryConfig.KAFKA_BOOTSTRAP, InventoryConfig.SCHEMA_REGISTRY_URL)
        }

        test("値を読む(期間は ISO 8601 と Kotlin の表記)") {
            val config =
                InventoryConfig
                    .fromEnvironment(
                        BASE +
                            mapOf(
                                "INVENTORY_KAFKA_BOOTSTRAP" to "kafka:9092",
                                "INVENTORY_SCHEMA_REGISTRY_URL" to "http://apicurio:8080/apis/registry/v3",
                                "INVENTORY_HEALTH_PORT" to "0",
                                "INVENTORY_SETTLED_RETENTION" to "P45D",
                                "INVENTORY_PURGE_INTERVAL" to "10m",
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
                    InventoryConfig.fromEnvironment(
                        mapOf(
                            "INVENTORY_HEALTH_PORT" to "70000",
                            "INVENTORY_SETTLED_RETENTION" to "13d",
                            "INVENTORY_PURGE_INTERVAL" to "soon",
                        ),
                    ) as Result.Err
                ).error
            error.violations.map { it.field }.toSet() shouldBe
                setOf("INVENTORY_DB_URL", "INVENTORY_HEALTH_PORT", "INVENTORY_SETTLED_RETENTION", "INVENTORY_PURGE_INTERVAL")
        }

        test("コマンド: 知らないサブコマンドと、serve の環境の所有者のパスワードは使い方の誤り(2)") {
            InventoryCommands.run(listOf("unknown"), BASE) shouldBe InventoryCommands.USAGE
            InventoryCommands.run(listOf("serve"), BASE + ("INVENTORY_DB_PASSWORD" to "x")) shouldBe InventoryCommands.USAGE
            InventoryCommands.run(listOf("serve"), BASE + ("INVENTORY_DB_PASSWORD_FILE" to "/run/x")) shouldBe InventoryCommands.USAGE
            // 設定の誤り(Kafka がない)も 2
            InventoryCommands.run(listOf("serve"), BASE + ("INVENTORY_APP_DB_PASSWORD" to "x")) shouldBe InventoryCommands.USAGE
            // migrate の所有者のパスワードがない
            InventoryCommands.run(listOf("migrate"), BASE) shouldBe InventoryCommands.USAGE
        }
    })
