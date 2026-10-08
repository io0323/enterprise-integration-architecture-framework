package io.eia.legacyorderacl.app

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ReconcileConfigSpec :
    FunSpec({
        test("DB の URL がなければ照合をしない(null)") {
            ReconcileConfig.fromEnvironment(emptyMap()) shouldBe Result.Ok(null)
        }

        test("既定値: ロール eiaf_reconcile・15 分ごと・比べ直しまで 30 秒・待ちの上限 2 分。スロットの接続先は同じ URL") {
            val config = ReconcileConfig.fromEnvironment(mapOf(ReconcileConfig.DB_URL to "jdbc:postgresql://db:5432/legacy_sim"))
            config.shouldBeInstanceOf<Result.Ok<ReconcileConfig?>>().value shouldBe
                ReconcileConfig(
                    "jdbc:postgresql://db:5432/legacy_sim",
                    "jdbc:postgresql://db:5432/legacy_sim",
                    "eiaf_reconcile",
                    15.minutes,
                    30.seconds,
                    2.minutes,
                )
        }

        test("期間は Kotlin の表記と ISO 8601 を受け付け、正でなければすべての違反を返す") {
            val ok =
                ReconcileConfig.fromEnvironment(
                    mapOf(
                        ReconcileConfig.DB_URL to "jdbc:postgresql://replica:5432/legacy_sim",
                        ReconcileConfig.SLOT_DB_URL to "jdbc:postgresql://primary:5432/legacy_sim",
                        ReconcileConfig.INTERVAL to "2m",
                        ReconcileConfig.RECHECK_AFTER to "PT10S",
                    ),
                ) as Result.Ok
            ok.value?.interval shouldBe 2.minutes
            ok.value?.recheckAfter shouldBe 10.seconds
            ok.value?.slotDbUrl shouldBe "jdbc:postgresql://primary:5432/legacy_sim"

            val error =
                ReconcileConfig
                    .fromEnvironment(
                        mapOf(
                            ReconcileConfig.DB_URL to "jdbc:mysql://x",
                            ReconcileConfig.INTERVAL to "0s",
                            ReconcileConfig.WAIT_TIMEOUT to "soon",
                        ),
                    ).shouldBeInstanceOf<Result.Err<ValidationError>>()
                    .error
            error.violations.map { it.field }.toSet() shouldBe
                setOf(ReconcileConfig.DB_URL, ReconcileConfig.INTERVAL, ReconcileConfig.WAIT_TIMEOUT)
        }

        test("照合の設定の誤りは、ACL の設定の誤りになる") {
            val error =
                AclConfig
                    .fromEnvironment(
                        mapOf(
                            AclConfig.KAFKA_BOOTSTRAP to "k:9092",
                            AclConfig.SCHEMA_REGISTRY_URL to "http://r",
                            ReconcileConfig.DB_URL to "x",
                        ),
                    ).shouldBeInstanceOf<Result.Err<ValidationError>>()
                    .error
            error.violations.map { it.field } shouldBe listOf(ReconcileConfig.DB_URL)
        }
    })
