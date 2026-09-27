package io.eia.platform.observability.propagation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.context.propagation.TextMapSetter

private object MapGetter : TextMapGetter<Map<String, String>> {
    override fun keys(carrier: Map<String, String>): Iterable<String> = carrier.keys

    override fun get(
        carrier: Map<String, String>?,
        key: String,
    ): String? = carrier?.get(key)
}

private object MapSetter : TextMapSetter<MutableMap<String, String>> {
    override fun set(
        carrier: MutableMap<String, String>?,
        key: String,
        value: String,
    ) {
        carrier?.put(key, value)
    }
}

private fun TextMapPropagator.extracted(headers: Map<String, String>): SpanContext =
    Span.fromContext(extract(Context.root(), headers, MapGetter)).spanContext

private fun TextMapPropagator.injected(spanContext: SpanContext): Map<String, String> =
    mutableMapOf<String, String>().also { inject(Context.root().with(Span.wrap(spanContext)), it, MapSetter) }

private const val HEX = "0123456789abcdef"

/** 小文字 16 進の [length] 文字(全 0 を除く)。 */
private fun arbHex(length: Int): Arb<String> =
    arbitrary { rs ->
        generateSequence { (1..length).map { HEX[rs.random.nextInt(HEX.length)] }.joinToString("") }.first { id -> id.any { it != '0' } }
    }

private const val KEY_FIRST = "abcdefghijklmnopqrstuvwxyz"
private const val KEY_REST = "abcdefghijklmnopqrstuvwxyz0123456789_-*/"
private const val VALUE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$%&'()*+-./:;<>?@[]^_`{|}~"

private val arbKey: Arb<String> =
    arbitrary { rs ->
        KEY_FIRST[rs.random.nextInt(KEY_FIRST.length)] +
            (0 until rs.random.nextInt(0, 12)).map { KEY_REST[rs.random.nextInt(KEY_REST.length)] }.joinToString("")
    }
private val arbValue: Arb<String> =
    arbitrary { rs -> (1..rs.random.nextInt(1, 16)).map { VALUE_CHARS[rs.random.nextInt(VALUE_CHARS.length)] }.joinToString("") }

/** 正しい tracestate(キーは一意。メンバーは 0〜8 個、区切りの後に OWS を入れることがある)。 */
private val arbTraceState: Arb<String?> =
    arbitrary { rs ->
        val keys =
            Arb
                .list(arbKey, 0..8)
                .sample(rs)
                .value
                .distinct()
        val separator = if (Arb.boolean().sample(rs).value) "," else ", "
        keys.joinToString(separator) { "$it=${arbValue.sample(rs).value}" }.ifEmpty { null }
    }.orNull(0.2)

/** 正しい traceparent(版 00。trace-flags は Level 2 で定義済みの 00〜03)。 */
private val arbTraceParent: Arb<String> =
    arbitrary { rs ->
        val flags = Arb.int(0..3).sample(rs).value
        "00-${arbHex(32).sample(rs).value}-${arbHex(16).sample(rs).value}-0$flags"
    }

class EiaTraceContextPropagatorSpec :
    FunSpec({
        val eia = EiaTraceContextPropagator
        val otel = W3CTraceContextPropagator.getInstance()

        context("正しい入力では OTel 標準の W3CTraceContextPropagator と同じ結果になる(ADR-0018 §1)") {
            test("抽出") {
                checkAll(arbTraceParent, arbTraceState) { traceParent, traceState ->
                    val headers =
                        buildMap {
                            put("traceparent", traceParent)
                            traceState?.let { put("tracestate", it) }
                        }

                    eia.extracted(headers) shouldBe otel.extracted(headers)
                }
            }

            test("注入") {
                checkAll(arbHex(32), arbHex(16), Arb.int(0..3), arbTraceState) { traceId, spanId, flags, traceState ->
                    val state =
                        traceState?.let {
                            eia
                                .extracted(
                                    mapOf("traceparent" to "00-$traceId-$spanId-00", "tracestate" to it),
                                ).traceState
                        }
                    val spanContext =
                        SpanContext.create(traceId, spanId, TraceFlags.fromByte(flags.toByte()), state ?: TraceState.getDefault())

                    eia.injected(spanContext) shouldBe otel.injected(spanContext)
                }
            }

            test("抽出した値を注入すると、同じ traceparent と tracestate に戻る") {
                checkAll(arbTraceParent, Arb.of("rojo=00f067aa0ba902b7,congo=t61rcWkgMzE", "a=1")) { traceParent, traceState ->
                    val headers = mapOf("traceparent" to traceParent, "tracestate" to traceState)

                    eia.injected(eia.extracted(headers)) shouldBe headers
                }
            }
        }

        context("不正な入力の扱い(ADR-0018 §1 に記録した OTel 標準との違い)") {
            val valid = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

            test("前後の OWS: EIAF は取り除いて受け付け、OTel 標準は拒否する") {
                val headers = mapOf("traceparent" to " $valid\t")

                eia.extracted(headers).isValid shouldBe true
                otel.extracted(headers).isValid shouldBe false
            }

            test("未定義の trace-flags: EIAF は sampled と random だけを残し、OTel 標準はそのまま保つ") {
                val headers = mapOf("traceparent" to "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-ff")

                eia.extracted(headers).traceFlags.asHex() shouldBe "03"
                otel.extracted(headers).traceFlags.asHex() shouldBe "ff"
            }

            test("未知の上位版: どちらも受け付ける。フラグの扱いの違いは版 00 と同じ") {
                val headers = mapOf("traceparent" to "cc-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-ff-future")

                eia.extracted(headers).traceFlags.asHex() shouldBe "03"
                otel.extracted(headers).traceFlags.asHex() shouldBe "ff"
            }

            test("そのほかの不正な値は、どちらも拒否する") {
                listOf(
                    "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                    "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",
                    "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
                    "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
                    "$valid-extra",
                    "cc-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01x",
                    "",
                ).forEach { header ->
                    eia.extracted(mapOf("traceparent" to header)).isValid shouldBe false
                    otel.extracted(mapOf("traceparent" to header)).isValid shouldBe false
                }
            }

            test("不正な tracestate は全体を捨て、traceparent は使う") {
                listOf("noequals", "=v", "Upper=1", "a=1,a=2", (1..33).joinToString(",") { "k$it=v" }).forEach { state ->
                    val context = eia.extracted(mapOf("traceparent" to valid, "tracestate" to state))

                    context.isValid shouldBe true
                    context.traceState.isEmpty shouldBe true
                }
            }

            test("traceparent がない・不正なら、元の Context のまま") {
                val original = Context.root()

                eia.extract(original, emptyMap(), MapGetter) shouldBe original
                eia.extract(original, mapOf("traceparent" to "garbage"), MapGetter) shouldBe original
                eia.extract(original, null, MapGetter) shouldBe original
            }
        }

        test("無効な span は注入しない。random フラグは注入でも保つ") {
            eia.injected(SpanContext.getInvalid()) shouldBe emptyMap()
            eia.injected(
                SpanContext.create(
                    "4bf92f3577b34da6a3ce929d0e0e4736",
                    "00f067aa0ba902b7",
                    TraceFlags.fromByte(0x02),
                    TraceState.getDefault(),
                ),
            ) shouldBe mapOf("traceparent" to "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-02")
            eia.fields() shouldBe listOf("traceparent", "tracestate")
        }
    })
