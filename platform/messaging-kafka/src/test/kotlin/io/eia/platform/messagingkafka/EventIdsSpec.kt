package io.eia.platform.messagingkafka

import io.eia.shared.kernel.FixedClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.time.Instant

class EventIdsSpec :
    FunSpec({
        test("UUIDv7: 版は 7、variant は RFC 9562、先頭 48 ビットは Unix 時刻のミリ秒") {
            val now = Instant.parse("2026-10-07T01:02:03.456Z")
            val id = UUID.fromString(EventIds(FixedClock(now)).next().toString())

            id.version() shouldBe 7
            id.variant() shouldBe 2
            (id.mostSignificantBits ushr 16) shouldBe now.toEpochMilliseconds()
        }

        test("時刻が進めば、文字列の順(B-tree の順)も進む。同じミリ秒でも重複しない") {
            val first = EventIds(FixedClock(Instant.parse("2026-10-07T00:00:00.001Z"))).next().toString()
            val second = EventIds(FixedClock(Instant.parse("2026-10-07T00:00:00.002Z"))).next().toString()
            (first < second) shouldBe true

            val ids = EventIds(FixedClock(Instant.parse("2026-10-07T00:00:00Z")))
            List(1_000) { ids.next() }.toSet() shouldHaveSize 1_000
        }
    })
