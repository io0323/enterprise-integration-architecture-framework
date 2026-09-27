package io.eia.platform.observability.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.ConsoleAppender
import io.eia.platform.observability.TestTelemetry
import io.eia.platform.observability.context.LogKeys
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Severity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant

private const val SECRET_MESSAGE = "login user=a@example.com password=hunter2 token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig"
private val SECRETS = listOf("a@example.com", "hunter2", "eyJhbGciOiJIUzI1NiJ9")

/** テストごとに独立した LoggerContext(アプリの logback 設定と干渉しない)。 */
private class IsolatedLogging(
    format: String = "json",
    bufferSize: Int = OtlpLogAppender.DEFAULT_BUFFER_SIZE,
) {
    val context =
        LoggerContext().apply {
            name = "test-${System.nanoTime()}"
            // SLF4J が初期化した LoggerContext と同じ MDC を使う(独立した LoggerContext は MDC を持たない)
            mdcAdapter = org.slf4j.MDC.getMDCAdapter()
            start()
        }
    val encoder =
        EiaLogEncoder().apply {
            this.context = this@IsolatedLogging.context
            service = "order-service"
            this.format = format
            start()
        }
    val otlp =
        OtlpLogAppender().apply {
            this.context = this@IsolatedLogging.context
            this.bufferSize = bufferSize
            start()
        }
    val logger: ch.qos.logback.classic.Logger = context.getLogger("io.eia.sample.OrderRoutes")

    init {
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(otlp)
    }

    fun encode(
        message: String,
        mdc: Map<String, String> = emptyMap(),
        throwable: Throwable? = null,
    ): String {
        val event = LoggingEvent("fqcn", logger, Level.INFO, message, throwable, null)
        event.mdcPropertyMap = mdc
        return encoder.encode(event).toString(Charsets.UTF_8)
    }
}

private fun String.json(): JsonObject = Json.parseToJsonElement(this).jsonObject

class LoggingSpec :
    FunSpec({
        context("EiaLogEncoder(標準出力)") {
            test("JSON は必須キーをすべて持ち、値のないキーは null にする(CODING_STANDARDS「ロギング」)") {
                val line = IsolatedLogging().encode("注文を受け付けました", mapOf(LogKeys.CORRELATION_ID to "c-1"))
                val json = line.json()

                line.endsWith("\n") shouldBe true
                json.keys.toList().take(8) shouldContainExactly
                    listOf("timestamp", "level", "service", "trace_id", "span_id", "correlation_id", "integration_id", "message")
                json["service"]?.jsonPrimitive?.content shouldBe "order-service"
                json["correlation_id"]?.jsonPrimitive?.content shouldBe "c-1"
                json["trace_id"] shouldBe JsonNull
                Instant.parse(json["timestamp"]?.jsonPrimitive?.content)
            }

            test("メッセージ・例外・MDC の値を伏せる") {
                val line =
                    IsolatedLogging().encode(
                        SECRET_MESSAGE,
                        mapOf("customer" to "b@example.com"),
                        IllegalStateException("failed for password=hunter2"),
                    )

                (SECRETS + "b@example.com").forEach { line shouldNotContain it }
                line.json()["exception"]?.jsonPrimitive?.content shouldContain "IllegalStateException"
            }

            test("EIA_LOG_FORMAT=console は人が読む 1 行にする。これも伏せる") {
                val line =
                    IsolatedLogging(format = "Console").encode(
                        SECRET_MESSAGE,
                        mapOf(LogKeys.CORRELATION_ID to "c-1", LogKeys.TRACE_ID to "4bf92f3577b34da6a3ce929d0e0e4736"),
                    )

                line shouldContain "INFO  [order-service] OrderRoutes - login user=[email] password=***"
                line shouldContain "(trace_id=4bf92f3577b34da6a3ce929d0e0e4736 correlation_id=c-1)"
                SECRETS.forEach { line shouldNotContain it }
            }

            test("必須キーと同じ名前の MDC のキーは mdc. を付け、必須キーを上書きさせない") {
                val json = IsolatedLogging().encode("本物", mapOf("message" to "偽物", "level" to "ERROR", "order" to "o-1")).json()

                json["message"]?.jsonPrimitive?.content shouldBe "本物"
                json["level"]?.jsonPrimitive?.content shouldBe "INFO"
                json["mdc.message"]?.jsonPrimitive?.content shouldBe "偽物"
                json["mdc.level"]?.jsonPrimitive?.content shouldBe "ERROR"
                json["order"]?.jsonPrimitive?.content shouldBe "o-1"
            }

            test("console でも 1 イベント 1 行にする(改行で別の行を偽装させない)") {
                val line = IsolatedLogging(format = "console").encode("注文\n2026-01-01T00:00:00Z ERROR [x] 偽の行\r")

                line.trimEnd('\n').lines() shouldHaveSize 1
                line shouldContain "注文\\n2026-01-01T00:00:00Z ERROR [x] 偽の行\\r"
            }

            test("不正な形式は json にして、logback の状態に警告を残す") {
                val logging = IsolatedLogging(format = "yaml")

                logging
                    .encode("x")
                    .json()["message"]
                    ?.jsonPrimitive
                    ?.content shouldBe "x"
                logging.context.statusManager.copyOfStatusList
                    .any { it.message.contains(LogFormat.ENV) } shouldBe true
                LogFormat.parse(" JSON ") shouldBe LogFormat.JSON
                LogFormat.parse(null) shouldBe null
            }
        }

        context("OtlpLogAppender と初期化前のログ(ADR-0018 §3)") {
            test("初期化前のログは標準出力に必ず出て、OTLP には初期化時に元の時刻で送る") {
                val logging = IsolatedLogging()
                val out = ByteArrayOutputStream()
                val original = System.out
                System.setOut(PrintStream(out, true, Charsets.UTF_8))
                try {
                    val console =
                        ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply {
                            context = logging.context
                            encoder = logging.encoder
                            start()
                        }
                    logging.context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(console)
                    logging.logger.info("起動しています {}", SECRET_MESSAGE)
                } finally {
                    System.setOut(original)
                }
                val stdout = out.toString(Charsets.UTF_8)
                val loggedAt = Instant.parse(stdout.json()["timestamp"]?.jsonPrimitive?.content)

                stdout shouldContain "起動しています"
                SECRETS.forEach { stdout shouldNotContain it }
                logging.otlp.pendingCount() shouldBe 1

                TestTelemetry(installLogAppender = false).use { telemetry ->
                    OtlpLogAppender.install(telemetry.runtime.sdk.sdkLoggerProvider, logging.context)
                    val record = telemetry.logs.single()

                    logging.otlp.pendingCount() shouldBe 0
                    record.bodyValue?.asString().orEmpty() shouldStartWith "起動しています"
                    SECRETS.forEach { record.bodyValue?.asString().orEmpty() shouldNotContain it }
                    Instant.ofEpochSecond(0, record.timestampEpochNanos) shouldBe loggedAt
                }
            }

            test("溢れた分は捨て、件数を警告として送る") {
                val logging = IsolatedLogging(bufferSize = 2)
                repeat(5) { logging.logger.info("起動中 $it") }

                TestTelemetry(installLogAppender = false).use { telemetry ->
                    OtlpLogAppender.install(telemetry.runtime.sdk.sdkLoggerProvider, logging.context)
                    val bodies = telemetry.logs.map { it.bodyValue?.asString() }

                    bodies shouldContainExactly listOf("起動中 0", "起動中 1", "OTel の初期化前のログを 3 件破棄しました(bufferSize=2)")
                    telemetry.logs.last().severity shouldBe Severity.WARN
                }
            }

            test("初期化後は直接送る。MDC の ID を trace と属性に、例外を semconv の属性にし、値は伏せる") {
                val logging = IsolatedLogging()
                TestTelemetry(installLogAppender = false).use { telemetry ->
                    OtlpLogAppender.install(telemetry.runtime.sdk.sdkLoggerProvider, logging.context)
                    org.slf4j.MDC.setContextMap(
                        mapOf(
                            LogKeys.TRACE_ID to "4bf92f3577b34da6a3ce929d0e0e4736",
                            LogKeys.SPAN_ID to "00f067aa0ba902b7",
                            LogKeys.CORRELATION_ID to "c-1",
                            LogKeys.INTEGRATION_ID to "INT-SALES-001",
                            "customer" to "b@example.com",
                        ),
                    )
                    try {
                        logging.logger.error("失敗しました", IllegalStateException("password=hunter2"))
                        // 送信部自身のログは送り返さない
                        logging.context.getLogger("io.opentelemetry.exporter.Some").warn("export failed")
                    } finally {
                        org.slf4j.MDC.clear()
                    }
                    val record = telemetry.logs.single()
                    val attributes = record.attributes

                    record.spanContext.traceId shouldBe "4bf92f3577b34da6a3ce929d0e0e4736"
                    record.spanContext.spanId shouldBe "00f067aa0ba902b7"
                    record.severity shouldBe Severity.ERROR
                    attributes.get(AttributeKey.stringKey(LogKeys.CORRELATION_ID)) shouldBe "c-1"
                    attributes.get(AttributeKey.stringKey(LogKeys.INTEGRATION_ID)) shouldBe "INT-SALES-001"
                    attributes.get(AttributeKey.stringKey("customer")) shouldBe "[email]"
                    attributes.get(AttributeKey.stringKey("exception.type")) shouldBe "java.lang.IllegalStateException"
                    attributes.get(AttributeKey.stringKey("exception.message")) shouldBe "password=***"
                    attributes.asMap().values.none { it.toString().contains("hunter2") } shouldBe true
                }
            }

            test("現在の span をフラグごと使い、属性と同じ名前の MDC のキーには mdc. を付ける") {
                val logging = IsolatedLogging()
                TestTelemetry(installLogAppender = false).use { telemetry ->
                    OtlpLogAppender.install(telemetry.runtime.sdk.sdkLoggerProvider, logging.context)
                    val span =
                        telemetry.runtime.tracer
                            .spanBuilder("child")
                            .startSpan()
                    org.slf4j.MDC.put("logger", "偽物")
                    try {
                        span.makeCurrent().use { logging.logger.info("子の span の中") }
                    } finally {
                        org.slf4j.MDC.clear()
                        span.end()
                    }
                    val record = telemetry.logs.single()

                    record.spanContext.spanId shouldBe span.spanContext.spanId
                    record.spanContext.traceFlags shouldBe span.spanContext.traceFlags
                    record.attributes.get(AttributeKey.stringKey("logger")) shouldBe "io.eia.sample.OrderRoutes"
                    record.attributes.get(AttributeKey.stringKey("mdc.logger")) shouldBe "偽物"
                }
            }

            test("記録と初期化が並行しても、溜めたイベントと直接送ったイベントを取りこぼさない") {
                val logging = IsolatedLogging(bufferSize = 100_000)
                TestTelemetry(installLogAppender = false).use { telemetry ->
                    val threads =
                        (1..4).map { t ->
                            Thread { repeat(500) { logging.logger.info("t$t-$it") } }.apply { start() }
                        }
                    Thread.sleep(5)
                    OtlpLogAppender.install(telemetry.runtime.sdk.sdkLoggerProvider, logging.context)
                    threads.forEach(Thread::join)

                    telemetry.logs
                        .map { it.bodyValue?.asString() }
                        .toSet()
                        .size shouldBe 2_000
                    logging.otlp.pendingCount() shouldBe 0
                }
            }

            test("アペンダが付いていない LoggerContext への install は何もしない") {
                OtlpLogAppender.appendersIn(LoggerContext()) shouldBe emptyList()
                OtlpLogAppender.appendersIn(null) shouldBe emptyList()
            }
        }
    })
