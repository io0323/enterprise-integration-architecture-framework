package io.eia.legacyorderacl.app

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class AclConfigSpec :
    FunSpec({
        test("必須の Kafka と Apicurio の接続先。グループとヘルスチェックのポートには既定値がある") {
            val config =
                AclConfig.fromEnvironment(
                    mapOf(
                        AclConfig.KAFKA_BOOTSTRAP to "kafka:9092",
                        AclConfig.SCHEMA_REGISTRY_URL to "http://apicurio:8080/apis/registry/v3",
                    ),
                )
            config.shouldBeInstanceOf<Result.Ok<AclConfig>>().value shouldBe
                AclConfig("kafka:9092", "http://apicurio:8080/apis/registry/v3", "legacy-order-acl.translate", 8081)
        }

        test("必須の値がない・ポートが不正なら、すべての違反を返す") {
            val error =
                AclConfig
                    .fromEnvironment(
                        mapOf(AclConfig.HEALTH_PORT to "70000"),
                    ).shouldBeInstanceOf<Result.Err<ValidationError>>()
                    .error
            error.violations.map { it.field } shouldBe
                listOf(AclConfig.KAFKA_BOOTSTRAP, AclConfig.SCHEMA_REGISTRY_URL, AclConfig.HEALTH_PORT)
        }
    })
