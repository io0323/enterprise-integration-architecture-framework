package io.eia.shared.sdk.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal val NOW: Instant = Instant.parse("2026-09-30T00:00:00Z")

internal fun httpDate(at: Instant): String = at.format(DateTimeComponents.Formats.RFC_1123)

class RetryAfterSpec :
    FunSpec({
        test("秒数") {
            RetryAfter.parse("7", NOW) shouldBe 7.seconds
            RetryAfter.parse(" 0 ", NOW) shouldBe Duration.ZERO
        }

        test("HTTP-date は現在時刻からの差にし、過去の日時は 0 にする") {
            httpDate(NOW + 90.seconds) shouldBe "Wed, 30 Sep 2026 00:01:30 GMT"
            RetryAfter.parse(httpDate(NOW + 90.seconds), NOW) shouldBe 90.seconds
            RetryAfter.parse(httpDate(NOW - 90.seconds), NOW) shouldBe Duration.ZERO
        }

        test("HTTP-date の時差の表記(+0900)も受け付ける") {
            RetryAfter.parse("Wed, 30 Sep 2026 09:01:30 +0900", NOW) shouldBe 90.seconds
        }

        test("解釈できない値・負の値・桁の多すぎる値・ない場合は null") {
            listOf(null, "", "soon", "-1", "1.5", "1234567890", "Wed, 31 Sep 2026 00:00:00 GMT").forEach {
                RetryAfter.parse(it, NOW) shouldBe
                    null
            }
        }
    })
