package io.eia.saasmock.app

import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.URI
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class SaasMockConfigSpec :
    FunSpec({
        test("既定と、指定した値") {
            val defaults = (SaasMockConfig.fromEnvironment(emptyMap()) as Result.Ok).value
            defaults shouldBe
                SaasMockConfig(
                    8080,
                    null,
                    listOf(5.seconds, 30.seconds, 2.minutes, 10.minutes, 1.hours, 3.hours),
                    1.seconds,
                    RateLimiter.Limit(20, 1.seconds),
                    0,
                )
            val set =
                SaasMockConfig.fromEnvironment(
                    mapOf(
                        "SAAS_MOCK_PORT" to "9000",
                        "SAAS_MOCK_WEBHOOK_URL" to "http://apisix:9080/sales/v1/webhooks/crm",
                        "SAAS_MOCK_WEBHOOK_RETRY" to "1s, 2s",
                        "SAAS_MOCK_DISPATCH_INTERVAL" to "200ms",
                        "SAAS_MOCK_RATE_LIMIT" to "5/10s",
                        "SAAS_MOCK_SEED_CUSTOMERS" to "120",
                    ),
                )
            (set as Result.Ok).value shouldBe
                SaasMockConfig(
                    9000,
                    URI("http://apisix:9080/sales/v1/webhooks/crm"),
                    listOf(1.seconds, 2.seconds),
                    200.milliseconds,
                    RateLimiter.Limit(5, 10.seconds),
                    120,
                )
        }

        test("不正な値は項目ごとに理由を返す") {
            val result =
                SaasMockConfig.fromEnvironment(
                    mapOf(
                        "SAAS_MOCK_PORT" to "70000",
                        "SAAS_MOCK_WEBHOOK_URL" to "ftp://x",
                        "SAAS_MOCK_WEBHOOK_RETRY" to "1s,never",
                        "SAAS_MOCK_DISPATCH_INTERVAL" to "0s",
                        "SAAS_MOCK_RATE_LIMIT" to "0/1s",
                        "SAAS_MOCK_SEED_CUSTOMERS" to "-1",
                    ),
                )
            (result as Result.Err).error.violations.map { it.field } shouldBe
                listOf(
                    "SAAS_MOCK_PORT",
                    "SAAS_MOCK_WEBHOOK_URL",
                    "SAAS_MOCK_WEBHOOK_RETRY",
                    "SAAS_MOCK_DISPATCH_INTERVAL",
                    "SAAS_MOCK_RATE_LIMIT",
                    "SAAS_MOCK_SEED_CUSTOMERS",
                )
        }

        test("署名の鍵: 今の鍵がなければ空(送らない)。次の鍵があれば両方") {
            SaasMockServer.webhookSecrets(EnvSecretProvider(emptyMap())) shouldBe emptyList()
            SaasMockServer.webhookSecrets(EnvSecretProvider(mapOf("SAAS_MOCK_WEBHOOK_SECRET_NEXT" to "n"))) shouldBe emptyList()
            SaasMockServer.webhookSecrets(EnvSecretProvider(mapOf("SAAS_MOCK_WEBHOOK_SECRET" to "c"))) shouldBe listOf(Secret("c"))
            SaasMockServer.webhookSecrets(
                EnvSecretProvider(mapOf("SAAS_MOCK_WEBHOOK_SECRET" to "c", "SAAS_MOCK_WEBHOOK_SECRET_NEXT" to "n")),
            ) shouldBe listOf(Secret("c"), Secret("n"))
        }

        test("Rate Limit: 使い切ったら再試行までの秒数(切り上げ)。時間で戻る。変えたら新しい上限で数える") {
            val time = TestTimeSource()
            val limiter = RateLimiter(2, 10.seconds, time)
            limiter.acquire() shouldBe null
            limiter.acquire() shouldBe null
            limiter.acquire() shouldBe 5
            time += 4.seconds
            limiter.acquire() shouldBe 1
            time += 1.seconds
            limiter.acquire() shouldBe null
            limiter.change(RateLimiter.Limit(1, 1.minutes))
            limiter.acquire() shouldBe 60
            RateLimiter.parse("20/1s") shouldBe RateLimiter.Limit(20, 1.seconds)
            listOf("20", "x/1s", "0/1s", "1/0s", "1/soon").forEach { RateLimiter.parse(it) shouldBe null }
        }
    })
