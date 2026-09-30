package io.eia.order.adapters.out.persistence

import io.eia.shared.kernel.FixedClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.security.SecureRandom
import java.util.UUID
import kotlin.time.Instant

private val AT = Instant.parse("2026-10-01T00:00:00.123Z")

class UuidV7OrderIdGeneratorSpec :
    FunSpec({
        test("版は 7、バリアントは RFC 9562(2)、先頭 48 ビットは Unix 時刻のミリ秒") {
            val uuid = UuidV7OrderIdGenerator(FixedClock(AT)).uuid()

            uuid.version() shouldBe 7
            uuid.variant() shouldBe 2
            (uuid.mostSignificantBits ushr 16) shouldBe AT.toEpochMilliseconds()
        }

        test("OrderId は 36 文字の小文字の UUID で、64 文字の上限に収まる") {
            val id = UuidV7OrderIdGenerator(FixedClock(AT)).next().value
            id.length shouldBe 36
            UUID.fromString(id).toString() shouldBe id
        }

        test("ミリ秒が進めば、文字列の順序も進む(時刻順に並ぶ)") {
            val ids =
                (0L until 50L).map { offset ->
                    UuidV7OrderIdGenerator(FixedClock(Instant.fromEpochMilliseconds(AT.toEpochMilliseconds() + offset))).next().value
                }
            ids.sorted() shouldBe ids
        }

        test("同じミリ秒でも、乱数の部分で一意になる(10,000 件)") {
            val generator = UuidV7OrderIdGenerator(FixedClock(AT), SecureRandom())
            (1..10_000).map { generator.next().value }.toSet().size shouldBe 10_000
        }
    })
