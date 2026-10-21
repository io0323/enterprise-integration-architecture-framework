package io.eia.webhookreceiver.adapters

import com.github.avrokotlin.avro4k.Avro
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.platform.webhook.StandardWebhooks
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.FixedClock
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.webhookreceiver.adapters.inbound.rest.MAX_BODY_BYTES
import io.eia.webhookreceiver.adapters.inbound.rest.WebhookMetrics
import io.eia.webhookreceiver.adapters.inbound.rest.WebhookReceiver
import io.eia.webhookreceiver.adapters.inbound.rest.WebhookSecrets
import io.eia.webhookreceiver.adapters.inbound.rest.webhookReceiver
import io.eia.webhookreceiver.adapters.out.outbox.WebhookIngestSchemas
import io.eia.webhookreceiver.adapters.out.outbox.WebhookReceivedV1
import io.eia.webhookreceiver.application.port.inbound.ReceiveWebhook
import io.eia.webhookreceiver.application.port.inbound.ReceiveWebhookUseCase
import io.eia.webhookreceiver.domain.ReceiptOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import org.apache.avro.Schema
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private fun contract(path: String): String = File(checkNotNull(System.getProperty("eia.contractsAvro")), path).readText()

private val NOW = Instant.parse("2026-10-21T00:00:00Z")
private const val CURRENT = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"
private const val PREVIOUS = "previous-secret-value"
private const val BODY = """{"type":"customer.created","timestamp":"2026-10-21T00:00:00Z","data":{"id":"cus_1","version":1}}"""
private val NAME = SecretName("WEBHOOK_RECEIVER_CRM_SECRET")

private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** 受け取ったコマンドを残し、決めた結果を返す。 */
private class FakeReceive(
    var result: Result<ReceiptOutcome, DomainError> = ok(ReceiptOutcome.Recorded),
) : ReceiveWebhookUseCase {
    val commands = mutableListOf<ReceiveWebhook>()

    override suspend fun invoke(command: ReceiveWebhook): Result<ReceiptOutcome, DomainError> = result.also { commands += command }
}

class WebhookAdaptersSpec :
    FunSpec({
        lateinit var receive: FakeReceive
        lateinit var reader: InMemoryMetricReader
        lateinit var metrics: WebhookMetrics
        var env = mapOf(NAME.value to CURRENT)

        beforeTest {
            receive = FakeReceive()
            reader = InMemoryMetricReader.create()
            metrics =
                WebhookMetrics(
                    SdkMeterProvider
                        .builder()
                        .registerMetricReader(reader)
                        .build()
                        .get("test"),
                )
            env = mapOf(NAME.value to CURRENT)
        }

        /** 数えた要求の属性(`outcome/reason/key`)。 */
        fun counted(): List<String> =
            reader.collectAllMetrics().flatMap { metric ->
                metric.longSumData.points.flatMap { point ->
                    val a = point.attributes.asMap().mapKeys { it.key.key }
                    List(point.value.toInt()) { "${a["outcome"]}/${a["reason"]}/${a["key"]}" }
                }
            }

        fun withReceiver(block: suspend ApplicationTestBuilder.() -> Unit) =
            testApplication {
                routing {
                    webhookReceiver(
                        "/v1/webhooks/crm",
                        "crm",
                        WebhookReceiver(
                            receive,
                            WebhookSecrets({ name -> EnvSecretProvider(env).get(name) }, NAME),
                            metrics,
                            FixedClock(NOW),
                        ),
                    )
                }
                block()
            }

        fun HttpRequestBuilder.signed(
            body: ByteArray,
            secret: String = CURRENT,
            id: String = "evt_1",
            at: Instant = NOW,
        ) {
            header(StandardWebhooks.ID, id)
            header(StandardWebhooks.TIMESTAMP, at.epochSeconds.toString())
            header(StandardWebhooks.SIGNATURE, StandardWebhooks.signatureHeader(listOf(Secret(secret)), id, at, body))
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        suspend fun ApplicationTestBuilder.send(block: HttpRequestBuilder.() -> Unit): HttpResponse = client.post("/v1/webhooks/crm", block)

        test("署名が正しければ 204。本文は解析せず、受け取ったバイト列のまま、ハッシュと一緒にユースケースに渡す") {
            withReceiver {
                // 空白や順序を含めて、送り手のバイト列のまま署名を検証し、そのまま渡す
                val body = BODY.replace(",", ", ").toByteArray()
                send { signed(body) }.status shouldBe HttpStatusCode.NoContent
            }
            val command = receive.commands.single()
            command.webhookId shouldBe "evt_1"
            command.source shouldBe "crm"
            command.signedAt shouldBe NOW
            command.contentType shouldBe "application/json"
            command.body.decodeToString() shouldBe BODY.replace(",", ", ")
            command.bodySha256 shouldBe sha256(command.body)
            counted() shouldContainExactlyInAnyOrder listOf("recorded/none/current")
        }

        test("鍵の差し替えの間は、差し替え前の鍵(_PREVIOUS)の署名も受け付ける。外した後は受け付けない") {
            env = mapOf(NAME.value to CURRENT, "${NAME.value}_PREVIOUS" to PREVIOUS)
            withReceiver {
                send { signed(BODY.toByteArray(), secret = PREVIOUS) }.status shouldBe HttpStatusCode.NoContent
                env = mapOf(NAME.value to CURRENT)
                send { signed(BODY.toByteArray(), secret = PREVIOUS) }.status shouldBe HttpStatusCode.Unauthorized
            }
            counted() shouldContainExactlyInAnyOrder listOf("recorded/none/previous", "rejected/webhook_signature_invalid/none")
        }

        test("署名の不一致・時刻の範囲外は 401(種類を Problem Details で返す)。ユースケースは呼ばない") {
            withReceiver {
                val tampered =
                    send {
                        signed(BODY.toByteArray())
                        setBody(BODY.replace("cus_1", "cus_2").toByteArray())
                    }
                tampered.status shouldBe HttpStatusCode.Unauthorized
                tampered.bodyAsText() shouldContain "https://eiaf.example/problems/webhook-signature-invalid"
                send { signed(BODY.toByteArray(), secret = "other") }.status shouldBe HttpStatusCode.Unauthorized

                // リプレイ: 正しい署名でも、署名の時刻が 5 分より古ければ拒否する
                val replay = send { signed(BODY.toByteArray(), at = NOW - 5.minutes - 1.minutes) }
                replay.status shouldBe HttpStatusCode.Unauthorized
                replay.bodyAsText() shouldContain "https://eiaf.example/problems/webhook-timestamp-out-of-range"
                // 送り手が署名し直さずに、3 時間前の時刻のまま再送してきたら拒否する(正当な再送は新しい時刻で署名し直す。ADR-0035 §2)
                send { signed(BODY.toByteArray(), at = NOW - 3.hours) }.status shouldBe HttpStatusCode.Unauthorized
            }
            receive.commands.shouldBeEmpty()
            counted().count { it.startsWith("rejected/webhook_signature_invalid") } shouldBe 2
            counted().count { it.startsWith("rejected/webhook_timestamp_out_of_range") } shouldBe 2
        }

        test("ヘッダの欠落・形式の誤りは 400。本文が 256 KiB を超えたら 413") {
            withReceiver {
                send { setBody(BODY) }.status shouldBe HttpStatusCode.BadRequest
                send {
                    signed(BODY.toByteArray())
                    header(StandardWebhooks.TIMESTAMP, "yesterday")
                }.status shouldBe HttpStatusCode.BadRequest
                val large = ByteArray(MAX_BODY_BYTES + 1) { 'a'.code.toByte() }
                send { signed(large) }.status shouldBe HttpStatusCode.PayloadTooLarge
                send { signed(ByteArray(MAX_BODY_BYTES) { 'a'.code.toByte() }) }.status shouldBe HttpStatusCode.NoContent
            }
            receive.commands.size shouldBe 1
            counted().count { it == "rejected/webhook_body_too_large/none" } shouldBe 1
            counted().count { it.startsWith("rejected/webhook_header_") } shouldBe 2
        }

        test("鍵を読めない・記録を書けないときは 503 と Retry-After(送り手に再送させる)") {
            env = emptyMap()
            withReceiver {
                val noKey = send { signed(BODY.toByteArray()) }
                noKey.status shouldBe HttpStatusCode.ServiceUnavailable
                noKey.headers[HttpHeaders.RetryAfter] shouldBe "5"
                env = mapOf(NAME.value to CURRENT)
                receive.result = err(UnavailableError("db"))
                send { signed(BODY.toByteArray()) }.status shouldBe HttpStatusCode.ServiceUnavailable
            }
            counted() shouldContainExactlyInAnyOrder listOf("failed/webhook_secret_unavailable/none", "failed/unavailable/none")
        }

        test("同じ ID の再送にも 204。本文が違う再送は別に数える") {
            withReceiver {
                receive.result = ok(ReceiptOutcome.Duplicate(sameBody = true))
                send { signed(BODY.toByteArray()) }.status shouldBe HttpStatusCode.NoContent
                receive.result = ok(ReceiptOutcome.Duplicate(sameBody = false))
                send { signed(BODY.toByteArray()) }.status shouldBe HttpStatusCode.NoContent
            }
            counted() shouldContainExactlyInAnyOrder listOf("duplicate/none/current", "duplicate_body_mismatch/none/current")
        }

        test("取り込みのスキーマは契約のファイルと同じで、イベントの型を契約のスキーマで読み書きできる(本文はバイト列のまま)") {
            WebhookIngestSchemas.subjects.map { it.topic } shouldBe listOf("integration.crm-webhook.received.v1")
            WebhookIngestSchemas.webhookReceived.schema shouldBe contract("integration/WebhookReceived.avsc")
            val schema = Schema.Parser().parse(WebhookIngestSchemas.webhookReceived.schema)
            val event =
                WebhookReceivedV1(
                    "evt_1",
                    "crm",
                    Instant.parse("2026-10-21T00:00:00Z"),
                    Instant.parse("2026-10-21T00:00:01.123456Z"),
                    "application/json",
                    sha256(BODY.toByteArray()),
                    BODY.toByteArray(),
                )
            val decoded =
                Avro.decodeFromByteArray(
                    schema,
                    WebhookReceivedV1.serializer(),
                    Avro.encodeToByteArray(schema, WebhookReceivedV1.serializer(), event),
                )
            decoded.receivedAt shouldBe event.receivedAt
            decoded.body.decodeToString() shouldBe BODY
            event.toString() shouldNotContain "cus_1"
        }
    })
