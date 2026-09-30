package io.eia.platform.reliability

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant

internal val NOW: Instant = Instant.parse("2026-09-30T00:00:00Z")

internal fun httpDate(at: Instant): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(at.toJavaInstant().atOffset(ZoneOffset.UTC))

class RetryAfterSpec :
    FunSpec({
        test("秒数") {
            RetryAfter.parse("7", NOW) shouldBe 7.seconds
            RetryAfter.parse(" 0 ", NOW) shouldBe Duration.ZERO
        }

        test("HTTP-date は現在時刻からの差にし、過去の日時は 0 にする") {
            RetryAfter.parse(httpDate(NOW + 90.seconds), NOW) shouldBe 90.seconds
            RetryAfter.parse(httpDate(NOW - 90.seconds), NOW) shouldBe Duration.ZERO
        }

        test("解釈できない値・負の値・桁の多すぎる値・ない場合は null") {
            listOf(null, "", "soon", "-1", "1.5", "1234567890").forEach { RetryAfter.parse(it, NOW) shouldBe null }
        }
    })
