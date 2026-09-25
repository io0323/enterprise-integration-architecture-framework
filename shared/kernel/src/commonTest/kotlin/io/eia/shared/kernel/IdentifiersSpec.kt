package io.eia.shared.kernel

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import kotlin.random.Random
import kotlin.time.Instant

private const val UUID_V4 = "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"

class IdentifiersSpec :
    FunSpec({
        context("CorrelationId") {
            test("許可された文字で 1〜128 文字なら受け付ける") {
                CorrelationId.parse("req-2026.09_25").shouldBeOk().value shouldBe "req-2026.09_25"
                CorrelationId.parse("a".repeat(128)).shouldBeOk().toString() shouldBe "a".repeat(128)
            }

            test("空・長すぎる・禁止文字を含む値を拒否する") {
                listOf("", "a".repeat(129), "has space", "改行\n", "a/b").forEach {
                    CorrelationId.parse(it).shouldBeErr().code shouldBe "validation_failed"
                }
            }

            test("UUIDv4 形式で生成し、乱数を固定すると同じ値になる") {
                CorrelationId.generate().value shouldMatch UUID_V4
                CorrelationId.generate(Random(1)).value shouldMatch UUID_V4
                CorrelationId.generate(Random(1)) shouldBe CorrelationId.generate(Random(1))
                CorrelationId.generate(Random(1)) shouldNotBe CorrelationId.generate(Random(2))
            }
        }

        context("IdempotencyKey") {
            test("表示可能な ASCII で 1〜255 文字なら受け付ける") {
                IdempotencyKey.parse("order:2026-09-25#1~!").shouldBeOk().value shouldBe "order:2026-09-25#1~!"
                IdempotencyKey.parse("k".repeat(255)).shouldBeOk().toString() shouldBe "k".repeat(255)
            }

            test("空・長すぎる・空白や非 ASCII を含む値を拒否する") {
                listOf("", "k".repeat(256), "a b", "キー", "tab\t").forEach {
                    IdempotencyKey.parse(it).shouldBeErr().code shouldBe "validation_failed"
                }
            }

            test("UUIDv4 形式で生成する") {
                IdempotencyKey.generate().value shouldMatch UUID_V4
                IdempotencyKey.generate(Random(7)) shouldBe IdempotencyKey.generate(Random(7))
            }
        }

        test("FixedClock は常に同じ時刻を返す") {
            val instant = Instant.parse("2026-09-25T00:00:00Z")
            val clock = FixedClock(instant)
            clock.now() shouldBe instant
            clock.now() shouldBe instant
        }
    })
