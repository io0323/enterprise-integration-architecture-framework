package io.eia.shared.resilience.trace

import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.random.Random

private const val VALID = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
private const val TRACEPARENT_V00 = "^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$"

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private fun Result<*, *>.shouldBeRejected() {
    if (this is Result.Ok) fail("拒否を期待しましたが受け付けました: $value")
}

class TraceParentSpec :
    FunSpec({
        context("parse") {
            test("W3C の例を解析し、各項目を取り出せる") {
                val parsed = TraceParent.parse(VALID).ok()

                parsed.traceId.value shouldBe "4bf92f3577b34da6a3ce929d0e0e4736"
                parsed.parentId.value shouldBe "00f067aa0ba902b7"
                parsed.sampled shouldBe true
                parsed.format() shouldBe VALID
            }

            test("前後の空白を取り除き、sampled でないフラグも読む") {
                val parsed = TraceParent.parse("  00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00 ").ok()

                parsed.sampled shouldBe false
                parsed.flags.toString() shouldBe "00"
            }

            test("未知の上位版は先頭 55 文字を読み、送信時は版 00 に戻す") {
                TraceParent.parse("cc-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-future").ok().format() shouldBe VALID
                TraceParent.parse("cc-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01").ok().format() shouldBe VALID
            }

            test("版 00 の trace-flags は sampled と random だけを残す(Level 2 §3.2.2.5.3)") {
                val parsed = TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-ff").ok()

                parsed.flags.value shouldBe 0x03
                parsed.flags.random shouldBe true
                parsed.format() shouldBe "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-03"
                parsed.child(Random(1)).flags.value shouldBe 0x03
            }

            test("未知の上位版の trace-flags は sampled だけを読む(Level 2 §3.2.4)") {
                TraceParent.parse("cc-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-ff-future").ok().format() shouldBe
                    "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
                TraceParent.parse("cc-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-fe").ok().format() shouldBe
                    "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00"
            }

            test("直接組み立てた値でも、送信時は未定義のフラグを 0 にする") {
                val parent = TraceParent.parse(VALID).ok().copy(flags = TraceFlags.of(0xfd).ok())

                parent.format() shouldBe "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
                TraceFlags
                    .of(0xfe)
                    .ok()
                    .outgoing()
                    .value shouldBe 0x02
            }

            test("不正な値を拒否する") {
                listOf(
                    "",
                    "00",
                    // 版 ff、版が 16 進でない
                    "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                    "0g-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                    // 版 00 に拡張部がある、長さが違う
                    "$VALID-extra",
                    "00-4bf92f3577b34da6a3ce929d0e0e473-00f067aa0ba902b7-01",
                    // 上位版の拡張部が '-' で区切られていない
                    "cc-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01x",
                    // 大文字の 16 進
                    "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",
                    // 全 0 の trace-id / parent-id
                    "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
                    "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
                    // 区切りの位置が違う・フラグが不正
                    "00_4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                    "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-0z",
                ).forEach { TraceParent.parse(it).shouldBeRejected() }
            }

            test("エラーは traceparent の検証エラーで、受信した値を含まない") {
                val secretLike = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-zz"
                when (val result = TraceParent.parse(secretLike)) {
                    is Result.Ok -> {
                        fail("拒否を期待しました")
                    }

                    is Result.Err -> {
                        result.error.violations
                            .single()
                            .field shouldBe TraceParent.HEADER
                        result.error.message.contains(secretLike) shouldBe false
                    }
                }
            }
        }

        context("generate / child") {
            test("生成した値は版 00 の形式で、解析し直すと同じになる") {
                checkAll(Arb.long(), Arb.boolean()) { seed, sampled ->
                    val generated = TraceParent.generate(Random(seed), sampled)

                    generated.format() shouldMatch TRACEPARENT_V00
                    generated.sampled shouldBe sampled
                    TraceParent.parse(generated.format()).ok() shouldBe generated
                }
            }

            test("乱数を固定すると同じ値になる") {
                TraceParent.generate(Random(7)) shouldBe TraceParent.generate(Random(7))
                TraceParent.generate(Random(7)) shouldNotBe TraceParent.generate(Random(8))
            }

            test("child は trace-id とフラグを引き継ぎ、parent-id だけを変える") {
                val parent = TraceParent.parse(VALID).ok()
                val child = parent.child(Random(1))

                child.traceId shouldBe parent.traceId
                child.flags shouldBe parent.flags
                child.parentId shouldNotBe parent.parentId
            }
        }

        context("ID と フラグ") {
            test("全 0 を返す乱数からでも全 0 の ID を作らない") {
                val zeros =
                    object : Random() {
                        private var calls = 0

                        override fun nextBits(bitCount: Int): Int = if (calls++ < 8) 0 else 1
                    }
                TraceId.generate(zeros).value.all { it == '0' } shouldBe false
            }

            test("SpanId と TraceId の単独の解析") {
                SpanId.parse("00f067aa0ba902b7").ok().toString() shouldBe "00f067aa0ba902b7"
                SpanId.parse("00F067AA0BA902B7").shouldBeRejected()
                TraceId.parse("abc").shouldBeRejected()
            }

            test("sampled ビットだけを切り替え、他のビットは保つ") {
                val flags = TraceFlags.of(0x03).ok()

                flags.withSampled(false).value shouldBe 0x02
                flags.withSampled(false).withSampled(true) shouldBe flags
                TraceFlags.SAMPLED.sampled shouldBe true
                TraceFlags.of(0x0a).ok().toString() shouldBe "0a"
            }

            test("1 バイトの範囲外の値は検証エラーにする(例外にしない)") {
                TraceFlags.of(0).ok() shouldBe TraceFlags.NONE
                TraceFlags.of(0xff).ok().value shouldBe 0xff
                TraceFlags.of(-1).shouldBeRejected()
                TraceFlags.of(0x100).shouldBeRejected()
            }
        }
    })
