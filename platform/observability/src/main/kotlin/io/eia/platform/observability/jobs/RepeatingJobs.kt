package io.eia.platform.observability.jobs

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * 常駐のプロセスが繰り返す仕事(見回り・期限切れの処理・消し込み)。想定していない例外でも止めずに次の回へ進む。
 *
 * ループが例外で止まっても、ほかのコルーチンとヘルスチェックは動き続けるので、外からは気づけない(#191)。
 * 仕事の中の失敗は `Result` で扱うのが原則で、ここが受けるのはそこから漏れた例外だけ。受けたら数えて、アラート `RepeatingJobFailing` で知らせる。
 * ログには例外の種類だけを残す(メッセージに業務のデータや接続先が入りうる)。
 *
 * | メトリクス | 属性 |
 * |---|---|
 * | `eia.job.unexpected_failures` | `eia.job.name` |
 */
public class RepeatingJobs(
    meter: Meter,
) {
    private val failures: LongCounter =
        meter
            .counterBuilder("eia.job.unexpected_failures")
            .setUnit("{failure}")
            .setDescription("繰り返しの仕事が想定していない例外で終わった回数")
            .build()

    /**
     * キャンセルされるまで [block] を [interval] ごとに繰り返す。
     *
     * @param delayFirst 最初の回の前にも待つ
     */
    public suspend fun repeat(
        job: String,
        interval: Duration,
        delayFirst: Boolean = false,
        block: suspend () -> Unit,
    ) {
        // 1 件目の失敗もアラートの increase で数えられるよう、起動時に 0 で系列を作る
        failures.add(0, attributes(job))
        if (delayFirst) delay(interval)
        while (currentCoroutineContext().isActive) {
            attempt(job, block)
            delay(interval)
        }
    }

    /** [block] を 1 回動かす。想定していない例外は数えて飲み込み、失敗なら false を返す。キャンセルはそのまま伝える。 */
    @Suppress("TooGenericExceptionCaught") // どの例外でも、繰り返しとほかの仕事を止めない
    public suspend fun attempt(
        job: String,
        block: suspend () -> Unit,
    ): Boolean =
        try {
            block()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures.add(1, attributes(job))
            logger.error("繰り返しの仕事 {} が想定していない例外で失敗しました。次の回にやり直します(error.type={})", job, e::class.qualifiedName)
            false
        }

    private fun attributes(job: String) = Attributes.of(JOB, job)

    private companion object {
        val JOB: AttributeKey<String> = AttributeKey.stringKey("eia.job.name")
        val logger = LoggerFactory.getLogger(RepeatingJobs::class.java)
    }
}
