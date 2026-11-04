package io.eia.platform.observability.jobs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.milliseconds

private class Fixture {
    val reader: InMemoryMetricReader = InMemoryMetricReader.create()
    val jobs =
        RepeatingJobs(
            SdkMeterProvider
                .builder()
                .registerMetricReader(reader)
                .build()
                .get("test"),
        )

    /** 仕事の名前ごとの失敗の回数。 */
    fun failures(): Map<String, Long> =
        reader
            .collectAllMetrics()
            .single { it.name == "eia.job.unexpected_failures" }
            .longSumData
            .points
            .associate { it.attributes.get(AttributeKey.stringKey("eia.job.name"))!! to it.value }
}

class RepeatingJobsSpec :
    FunSpec({
        test("想定していない例外が出ても、繰り返しは止まらず次の回に進む。失敗を仕事ごとに数える") {
            val fixture = Fixture()
            var runs = 0
            shouldThrow<CancellationException> {
                fixture.jobs.repeat("sweep", 1.milliseconds) {
                    runs++
                    when (runs) {
                        1 -> throw UnsupportedOperationException("壊れた入力")
                        2 -> Unit
                        else -> throw CancellationException("テストの終わり")
                    }
                }
            }
            runs shouldBe 3
            fixture.failures() shouldBe mapOf("sweep" to 1L)
        }

        test("失敗がなくても、起動の時点で 0 の系列を作る(1 件目の失敗をアラートの increase で数えるため)") {
            val fixture = Fixture()
            shouldThrow<CancellationException> {
                fixture.jobs.repeat("purge", 1.milliseconds) { throw CancellationException("テストの終わり") }
            }
            fixture.failures() shouldBe mapOf("purge" to 0L)
        }

        test("delayFirst: 最初の回の前にも待つ") {
            val fixture = Fixture()
            val started = System.nanoTime()
            var firstRunAfterNanos = 0L
            shouldThrow<CancellationException> {
                fixture.jobs.repeat("purge", 50.milliseconds, delayFirst = true) {
                    firstRunAfterNanos = System.nanoTime() - started
                    throw CancellationException("テストの終わり")
                }
            }
            (firstRunAfterNanos >= 50_000_000L) shouldBe true
        }

        test("attempt: 例外なら false、成功なら true。キャンセルはそのまま伝える") {
            val fixture = Fixture()
            fixture.jobs.attempt("run") { throw UnsupportedOperationException("x") } shouldBe false
            fixture.jobs.attempt("run") { } shouldBe true
            shouldThrow<CancellationException> { fixture.jobs.attempt("run") { throw CancellationException("x") } }
        }
    })
