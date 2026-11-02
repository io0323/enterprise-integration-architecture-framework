package io.eia.b2bgateway.app

import io.eia.platform.testsupport.TestLedgers
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.net.URI
import java.nio.file.Files
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private fun <T, E> Result<T, E>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private fun Result<*, ValidationError>.fields(): List<String> =
    when (this) {
        is Result.Ok -> fail("Err を期待しましたが Ok でした: $value")
        is Result.Err -> error.violations.map { it.field }
    }

class B2bGatewayConfigSpec :
    FunSpec({
        val minimal = mapOf(B2bGatewayConfig.DB_URL to "jdbc:postgresql://postgres:5432/b2b_gateway")

        test("既定: バケットは eiaf-b2b-originals、クライアントは eiaf-b2b-gateway、30 秒ごとに見回り・送信・取り込み、送り直しの上限は 12 時間") {
            val config = B2bGatewayConfig.fromEnvironment(minimal).ok()

            config.ownerUser shouldBe "b2b_gateway"
            config.appUser shouldBe "b2b_gateway_app"
            config.bucket shouldBe "eiaf-b2b-originals"
            config.clientId shouldBe "eiaf-b2b-gateway"
            config.healthPort shouldBe 8081
            config.intervals shouldBe B2bGatewayConfig.Intervals(30.seconds, 30.seconds, 30.seconds, 1.minutes)
            config.submitWindow shouldBe 12.hours
        }

        test("serve には、原本の置き場・保持期間・Gateway・トークンと、監査のアンカーの置き場・保持期間が要る(保持期間に既定は置かない)") {
            val required =
                listOf(
                    B2bGatewayConfig.S3_ENDPOINT,
                    B2bGatewayConfig.ORIGINAL_RETENTION,
                    B2bGatewayConfig.GATEWAY_URL,
                    B2bGatewayConfig.TOKEN_URL,
                )
            B2bGatewayConfig
                .fromEnvironment(minimal)
                .ok()
                .forServe()
                .fields() shouldContainExactlyInAnyOrder
                required + listOf(B2bGatewayConfig.AUDIT_S3_ENDPOINT, B2bGatewayConfig.ANCHOR_RETENTION)
            // アンカーを無効にすれば、その置き場は要らない
            val withoutAnchor = minimal + (B2bGatewayConfig.ANCHOR_ENABLED to "false")
            B2bGatewayConfig
                .fromEnvironment(withoutAnchor)
                .ok()
                .forServe()
                .fields() shouldContainExactlyInAnyOrder required

            val serve =
                B2bGatewayConfig
                    .fromEnvironment(
                        minimal +
                            mapOf(
                                B2bGatewayConfig.S3_ENDPOINT to "http://seaweedfs:8333",
                                B2bGatewayConfig.ORIGINAL_RETENTION to "P1D",
                                B2bGatewayConfig.GATEWAY_URL to "http://apisix:9080",
                                B2bGatewayConfig.TOKEN_URL to "http://keycloak:8080/realms/eiaf/protocol/openid-connect/token",
                                B2bGatewayConfig.AUDIT_S3_ENDPOINT to "http://seaweedfs:8333",
                                B2bGatewayConfig.ANCHOR_RETENTION to "P1D",
                            ),
                    ).ok()
                    .forServe()
                    .ok()
            serve.retention shouldBe 1.days
            serve.gatewayUrl shouldBe URI("http://apisix:9080")
        }

        test("誤り: DB の URL がない・期間や URL やポートの形が違う・送り直しの上限が 24 時間以上") {
            B2bGatewayConfig
                .fromEnvironment(
                    mapOf(
                        B2bGatewayConfig.POLL_INTERVAL to "soon",
                        B2bGatewayConfig.S3_ENDPOINT to "seaweedfs:8333",
                        B2bGatewayConfig.HEALTH_PORT to "70000",
                        B2bGatewayConfig.SUBMIT_WINDOW to "24h",
                        B2bGatewayConfig.ANCHOR_ENABLED to "yes",
                    ),
                ).fields() shouldContainExactlyInAnyOrder
                listOf(
                    B2bGatewayConfig.DB_URL,
                    B2bGatewayConfig.POLL_INTERVAL,
                    B2bGatewayConfig.S3_ENDPOINT,
                    B2bGatewayConfig.HEALTH_PORT,
                    B2bGatewayConfig.SUBMIT_WINDOW,
                    B2bGatewayConfig.ANCHOR_ENABLED,
                )
        }

        test("台帳: serve・codes では必須。読めない・形の違う台帳は設定の誤り。EDI の取引先を受け取りと応答の相手にする") {
            B2bGatewayConfig
                .fromEnvironment(minimal)
                .ok()
                .ledger()
                .fields() shouldContainExactly listOf(B2bGatewayConfig.PARTNERS_FILE)
            val missing = minimal + (B2bGatewayConfig.PARTNERS_FILE to "/nonexistent/partners.json")
            B2bGatewayConfig
                .fromEnvironment(missing)
                .ok()
                .ledger()
                .fields() shouldContainExactly listOf(B2bGatewayConfig.PARTNERS_FILE)

            val file = Files.createTempFile("partners", ".json")
            Files.writeString(file, TestLedgers.json(TestLedgers.Partner(), TestLedgers.Partner(id = "files-only", edi = false)))
            val ledger =
                B2bGatewayConfig
                    .fromEnvironment(minimal + (B2bGatewayConfig.PARTNERS_FILE to file.toString()))
                    .ok()
                    .ledger()
                    .ok()
            val partners = EdiPartners.of(ledger)
            partners.inbound shouldContainExactly listOf("partner01")
            partners.acknowledgements shouldContainExactly listOf("partner01")
            Files.delete(file)
        }

        test("コマンド: 使い方の誤りは 2。serve・codes の環境に所有者のパスワードがあれば 2") {
            B2bGatewayCommands.run(emptyList(), minimal) shouldBe B2bGatewayCommands.USAGE
            B2bGatewayCommands.run(listOf("unknown"), minimal) shouldBe B2bGatewayCommands.USAGE
            B2bGatewayCommands.run(listOf("codes", "import", "partner01", "items", "items.csv"), minimal) shouldBe B2bGatewayCommands.USAGE
            B2bGatewayCommands.run(listOf("codes", "import", "partner01", "colors", "x.csv", "--operator", "ops-1"), minimal) shouldBe
                B2bGatewayCommands.USAGE
            B2bGatewayCommands.run(listOf("codes", "import", "partner01", "items", "x.csv", "--operator", "a b"), minimal) shouldBe
                B2bGatewayCommands.USAGE
            B2bGatewayCommands.run(listOf("serve"), minimal + (B2bGatewayConfig.OWNER_PASSWORD.value to "x")) shouldBe
                B2bGatewayCommands.USAGE
            B2bGatewayCommands.run(listOf("serve"), minimal) shouldBe B2bGatewayCommands.USAGE
        }
    })
