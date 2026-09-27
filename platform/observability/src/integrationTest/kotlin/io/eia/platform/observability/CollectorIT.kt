package io.eia.platform.observability

import io.eia.platform.observability.context.CorrelationHeaders
import io.eia.platform.observability.context.ObservabilityContext
import io.eia.platform.observability.ktor.client.ClientObservability
import io.eia.platform.observability.ktor.server.ServerObservability
import io.eia.platform.testsupport.InfraImages
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.nondeterministic.eventuallyConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.cio.CIO as ServerCIO

private const val OTLP_HTTP_PORT = 4318
private const val HEALTH_PORT = 13133
private const val CORRELATION_ID = "it-order-0001"
private const val TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJvcmRlci1jbGllbnQifQ.c2lnbmF0dXJl"
private const val EMAIL = "hanako.sato@example.com"
private const val PAYLOAD = """{"customer":{"email":"$EMAIL","phone":"090-1234-5678"},"card":"4111111111111111"}"""

private val logger = LoggerFactory.getLogger("io.eia.sample.OrderRoutes")

/** OTLP JSON の span / log record(必要な項目だけ)。 */
private data class ExportedSpan(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String,
    val kind: Int,
    val attributes: Map<String, String>,
)

private data class ExportedLog(
    val traceId: String,
    val spanId: String,
    val body: String,
    val attributes: Map<String, String>,
)

private const val SPAN_KIND_SERVER = 2
private const val SPAN_KIND_CLIENT = 3

private fun JsonObject.array(key: String): JsonArray = this[key]?.jsonArray ?: JsonArray(emptyList())

private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()

/** OTLP の KeyValue の配列を、文字列の値の Map にする(値は種類によらず文字列にする)。 */
private fun JsonArray.keyValues(): Map<String, String> =
    associate { element ->
        val entry = element.jsonObject
        val value =
            entry["value"]?.jsonObject?.values?.firstOrNull()?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: it.toString()
            }
        entry.text("key") to value.orEmpty()
    }

private fun lines(content: String): List<JsonObject> =
    content
        .lineSequence()
        .filter { it.isNotBlank() }
        .map { Json.parseToJsonElement(it).jsonObject }
        .toList()

private fun spansOf(content: String): List<ExportedSpan> =
    lines(content).flatMap { line ->
        line.array("resourceSpans").flatMap { rs ->
            rs.jsonObject.array("scopeSpans").flatMap { ss ->
                ss.jsonObject.array("spans").map(JsonElement::jsonObject).map {
                    ExportedSpan(
                        it.text("traceId"),
                        it.text("spanId"),
                        it.text("parentSpanId"),
                        it["kind"]?.jsonPrimitive?.content?.toInt() ?: 0,
                        it.array("attributes").keyValues(),
                    )
                }
            }
        }
    }

private fun logsOf(content: String): List<ExportedLog> =
    lines(content).flatMap { line ->
        line.array("resourceLogs").flatMap { rl ->
            rl.jsonObject.array("scopeLogs").flatMap { sl ->
                sl.jsonObject.array("logRecords").map(JsonElement::jsonObject).map {
                    ExportedLog(
                        it.text("traceId"),
                        it.text("spanId"),
                        it["body"]?.jsonObject?.text("stringValue").orEmpty(),
                        it.array("attributes").keyValues(),
                    )
                }
            }
        }
    }

/**
 * 実際の OTel Collector(infra/local/images.env の版)に OTLP/HTTP で送り、Collector が受け取った内容で確かめる(ADR-0018)。
 * - クライアント → サーバで trace_id がつながり、サーバの span の親がクライアントの span になる
 * - ログが Collector に届き、trace_id と correlation_id を持つ
 * - 本文・トークン・個人情報は、Collector に届いたどのテレメトリにも含まれない
 */
class CollectorIT :
    FunSpec({
        // Collector の出力先。Docker の cp(copyFileFromContainer)は tmpfs の中を読めないため、ホストのディレクトリをマウントする。
        // Collector は非 root(uid 10001)で動くため、誰でも書けるようにする
        val out = Files.createTempDirectory("eiaf-collector-it")
        Files.setPosixFilePermissions(out, PosixFilePermissions.fromString("rwxrwxrwx"))
        val collector =
            GenericContainer(InfraImages.get("OTEL_COLLECTOR_IMAGE"))
                .withCopyFileToContainer(MountableFile.forClasspathResource("collector-it.yaml"), "/etc/otelcol/collector-it.yaml")
                .withCommand("--config=/etc/otelcol/collector-it.yaml")
                .withFileSystemBind(out.toString(), "/out", BindMode.READ_WRITE)
                .withExposedPorts(OTLP_HTTP_PORT, HEALTH_PORT)
                .waitingFor(Wait.forHttp("/").forPort(HEALTH_PORT))

        beforeSpec { collector.start() }
        afterSpec {
            collector.stop()
            out.toFile().deleteRecursively()
        }

        /** Collector が書き出した OTLP JSON。まだなければ空文字列(eventually で読み直す)。 */
        fun exported(path: String): String =
            out
                .resolve(path.removePrefix("/out/"))
                .takeIf { Files.exists(it) }
                ?.let(Files::readString)
                .orEmpty()

        test("クライアント → サーバで trace_id がつながり、ログが Collector に届く。本文・トークン・個人情報は届かない") {
            val endpoint = "http://${collector.host}:${collector.getMappedPort(OTLP_HTTP_PORT)}"
            val runtime = Observability.init(ObservabilityConfig.of("collector-it", otlpEndpoint = endpoint).orFail())
            val server =
                embeddedServer(ServerCIO, port = 0) {
                    install(ServerObservability) {
                        this.runtime = runtime
                        integrationId = "INT-SALES-001"
                    }
                    routing {
                        post("/v1/orders") {
                            call.receiveText() // 本文は読むが、ログには出さない
                            logger.info("注文を受け付けました")
                            call.respondText("{}", ContentType.Application.Json, HttpStatusCode.Created)
                        }
                    }
                }.start(wait = false)
            val port =
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port
            val client = HttpClient(CIO) { install(ClientObservability) { this.runtime = runtime } }

            try {
                withContext(ObservabilityContext(CorrelationId.parse(CORRELATION_ID).orFail())) {
                    client
                        .post("http://localhost:$port/v1/orders") {
                            bearerAuth(TOKEN)
                            header("Idempotency-Key", "it-key-1")
                            contentType(ContentType.Application.Json)
                            setBody(PAYLOAD)
                        }.status shouldBe HttpStatusCode.Created
                }
            } finally {
                client.close()
                server.stop(0, 0)
                runtime.close()
            }

            eventually(
                eventuallyConfig {
                    duration = 20.seconds
                    interval = 0.5.seconds
                },
            ) {
                val spans = spansOf(exported("/out/traces.jsonl"))
                val clientSpan = spans.filter { it.kind == SPAN_KIND_CLIENT }.shouldHaveSize(1).single()
                val serverSpan = spans.filter { it.kind == SPAN_KIND_SERVER }.shouldHaveSize(1).single()

                serverSpan.traceId shouldBe clientSpan.traceId
                serverSpan.parentSpanId shouldBe clientSpan.spanId
                serverSpan.attributes["correlation_id"] shouldBe CORRELATION_ID
                clientSpan.attributes["correlation_id"] shouldBe CORRELATION_ID
                serverSpan.attributes["http.route"] shouldBe "/v1/orders"

                val log = logsOf(exported("/out/logs.jsonl")).single { it.body == "注文を受け付けました" }
                log.traceId shouldBe serverSpan.traceId
                log.spanId shouldBe serverSpan.spanId
                log.attributes["correlation_id"] shouldBe CORRELATION_ID
                log.attributes["integration_id"] shouldBe "INT-SALES-001"
            }

            eventually(
                eventuallyConfig {
                    duration = 20.seconds
                    interval = 0.5.seconds
                },
            ) {
                exported("/out/metrics.jsonl").contains("http.server.request.duration") shouldBe true
            }
            val everything = listOf("/out/traces.jsonl", "/out/logs.jsonl", "/out/metrics.jsonl").joinToString("\n") { exported(it) }
            listOf(TOKEN, EMAIL, "090-1234-5678", "4111111111111111", "Idempotency-Key").forEach { everything shouldNotContain it }
        }
    })

private fun <T> Result<T, *>.orFail(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }
