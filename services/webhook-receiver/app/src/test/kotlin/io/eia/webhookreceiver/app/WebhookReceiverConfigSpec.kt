package io.eia.webhookreceiver.app

import io.eia.platform.security.tls.TlsFiles
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.webhookreceiver.domain.ReceiptRetention
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val BASE = mapOf("WEBHOOK_RECEIVER_DB_URL" to "jdbc:postgresql://postgres:5432/webhook_receiver")

class WebhookReceiverConfigSpec :
    FunSpec({
        test("既定値") {
            val config = (WebhookReceiverConfig.fromEnvironment(BASE) as Result.Ok).value
            config.ownerUser shouldBe "webhook_receiver"
            config.appUser shouldBe "webhook_receiver_app"
            config.cdcUser shouldBe "debezium"
            config.schemaRegistryUrl shouldBe null
            config.httpsPort shouldBe 8443
            config.healthPort shouldBe 8081
            config.tls shouldBe TlsFiles(WebhookReceiverConfig.TLS, null, null, null)
            config.allowedClients shouldBe setOf("apisix")
            config.requestBudget shouldBe 5.seconds
            config.retention shouldBe ReceiptRetention.DEFAULT
            config.purgeInterval shouldBe 1.hours
        }

        test("指定した値を読む") {
            val env =
                BASE +
                    mapOf(
                        "WEBHOOK_RECEIVER_HTTPS_PORT" to "9443",
                        "WEBHOOK_RECEIVER_HEALTH_PORT" to "9081",
                        "WEBHOOK_RECEIVER_TLS_CERT_FILE" to "/certs/webhook-receiver.crt",
                        "WEBHOOK_RECEIVER_TLS_ALLOWED_CLIENTS" to "apisix,other",
                        "WEBHOOK_RECEIVER_REQUEST_BUDGET" to "3s",
                        "WEBHOOK_RECEIVER_RECEIPT_RETENTION" to "P14D",
                        "WEBHOOK_RECEIVER_PURGE_INTERVAL" to "10m",
                        WebhookReceiverConfig.SCHEMA_REGISTRY_URL to "http://apicurio:8080/apis/registry/v3",
                    )
            val config = (WebhookReceiverConfig.fromEnvironment(env) as Result.Ok).value
            config.httpsPort shouldBe 9443
            config.healthPort shouldBe 9081
            config.tls.cert shouldBe "/certs/webhook-receiver.crt"
            config.allowedClients shouldBe setOf("apisix", "other")
            config.requestBudget shouldBe 3.seconds
            config.retention.value shouldBe 14.days
            config.purgeInterval shouldBe 10.minutes
            config.schemaRegistryUrl shouldBe "http://apicurio:8080/apis/registry/v3"
        }

        test("誤りは項目ごとにまとめて返す") {
            val env =
                mapOf(
                    "WEBHOOK_RECEIVER_HTTPS_PORT" to "8081",
                    "WEBHOOK_RECEIVER_REQUEST_BUDGET" to "soon",
                    "WEBHOOK_RECEIVER_RECEIPT_RETENTION" to "1d",
                    "WEBHOOK_RECEIVER_TLS_ALLOWED_CLIENTS" to " , ",
                )
            val violations =
                WebhookReceiverConfig
                    .fromEnvironment(env)
                    .shouldBeInstanceOf<Result.Err<ValidationError>>()
                    .error.violations
            violations.map { it.field } shouldBe
                listOf(
                    "WEBHOOK_RECEIVER_DB_URL",
                    "WEBHOOK_RECEIVER_REQUEST_BUDGET",
                    "WEBHOOK_RECEIVER_RECEIPT_RETENTION",
                    "WEBHOOK_RECEIVER_TLS_ALLOWED_CLIENTS",
                    "WEBHOOK_RECEIVER_HEALTH_PORT",
                )
        }

        test("コマンド: 使い方の誤りは 2。serve に所有者のパスワードを渡したら起動しない") {
            WebhookReceiverCommands.run(emptyList(), BASE) shouldBe WebhookReceiverCommands.USAGE
            WebhookReceiverCommands.run(listOf("serve"), BASE + ("WEBHOOK_RECEIVER_DB_PASSWORD" to "x")) shouldBe
                WebhookReceiverCommands.USAGE
            WebhookReceiverCommands.run(listOf("serve"), BASE) shouldBe WebhookReceiverCommands.USAGE
            WebhookReceiverCommands.run(listOf("migrate"), BASE) shouldBe WebhookReceiverCommands.USAGE
        }
    })
