package io.eia.legacysim.app

import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class LegacySimCommandsSpec :
    FunSpec({
        context("引数の解析") {
            test("migrate と simulate の各操作") {
                LegacySimCommands.parse(listOf("migrate")) shouldBe LegacySimCommands.Action.Migrate
                listOf("seed", "advance", "cancel", "delete").forEach { name ->
                    val action = LegacySimCommands.parse(listOf("simulate", name, "3"))
                    action.shouldBeInstanceOf<LegacySimCommands.Action.Simulate>().name shouldBe name
                }
                Anomaly.entries.forEach { kind ->
                    LegacySimCommands.parse(listOf("simulate", "anomaly", kind.argument)).shouldNotBeNull()
                }
            }

            test("誤った引数は使い方の誤り(null)") {
                listOf(
                    emptyList(),
                    listOf("serve"),
                    listOf("migrate", "extra"),
                    listOf("simulate", "seed"),
                    listOf("simulate", "seed", "0"),
                    listOf("simulate", "seed", "10001"),
                    listOf("simulate", "seed", "x"),
                    listOf("simulate", "rename", "1"),
                    listOf("simulate", "anomaly", "unknown"),
                ).forEach { LegacySimCommands.parse(it).shouldBeNull() }
            }
        }

        context("終了コード") {
            test("使い方の誤り・設定の誤りは 2 で、DB に接続しない") {
                val out = mutableListOf<String>()
                LegacySimCommands.run(listOf("simulate", "seed", "0"), emptyMap(), out::add) shouldBe LegacySimCommands.USAGE
                LegacySimCommands.run(listOf("migrate"), emptyMap(), out::add) shouldBe LegacySimCommands.USAGE
                LegacySimCommands.run(
                    listOf("migrate"),
                    mapOf(LegacySimConfig.DB_URL to "jdbc:postgresql://127.0.0.1:1/x"),
                    out::add,
                ) shouldBe LegacySimCommands.USAGE
                out shouldBe emptyList()
            }
        }

        context("設定") {
            test("既定のロール名") {
                val config = LegacySimConfig.fromEnvironment(mapOf(LegacySimConfig.DB_URL to "jdbc:postgresql://db:5432/legacy_sim"))
                config.shouldBeInstanceOf<Result.Ok<LegacySimConfig>>().value shouldBe
                    LegacySimConfig("jdbc:postgresql://db:5432/legacy_sim", "legacy_sim", "legacy_sim_app", "debezium")
            }

            test("URL がない・PostgreSQL でない・ロール名が識別子として安全でない") {
                LegacySimConfig.fromEnvironment(emptyMap()).shouldBeInstanceOf<Result.Err<*>>()
                LegacySimConfig.fromEnvironment(mapOf(LegacySimConfig.DB_URL to "jdbc:mysql://db/x")).shouldBeInstanceOf<Result.Err<*>>()
                LegacySimConfig
                    .fromEnvironment(
                        mapOf(LegacySimConfig.DB_URL to "jdbc:postgresql://db/x", "LEGACY_SIM_CDC_DB_USER" to "debezium; DROP TABLE t"),
                    ).shouldBeInstanceOf<Result.Err<*>>()
            }
        }
    })
