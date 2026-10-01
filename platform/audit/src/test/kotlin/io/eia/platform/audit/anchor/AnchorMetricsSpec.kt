package io.eia.platform.audit.anchor

import io.eia.platform.audit.AuditStorageUnavailable
import io.eia.platform.audit.jdbc.AuditLog
import io.eia.platform.audit.jdbc.FakeAuditDb
import io.eia.platform.audit.jdbc.appendAll
import io.eia.platform.audit.jdbc.testEvent
import io.eia.shared.kernel.getOrNull
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.time.Duration
import java.time.Instant

private val SERVICE = ServiceName.parse("order").getOrNull()!!
private val INTERVAL: Duration = Duration.ofHours(1)

/** メトリクスを読み、Prometheus のアラートの候補(ADR-0017 §8)と同じ条件を評価する。 */
private class Gauges(
    private val reader: InMemoryMetricReader,
) {
    private fun values(): Map<String, List<MetricData>> = reader.collectAllMetrics().groupBy { it.name }

    fun gauge(name: String): Double? =
        values()[name]
            ?.single()
            ?.doubleGaugeData
            ?.points
            ?.singleOrNull()
            ?.value

    fun checks(outcome: String): Long =
        values()["eia.audit.anchor.checks"]
            ?.single()
            ?.longSumData
            ?.points
            ?.filter { it.attributes.get(AttributeKey.stringKey("outcome")) == outcome }
            ?.sumOf { it.value } ?: 0

    /** `time() - eia_audit_anchor_last_success_seconds > 2 * eia_audit_anchor_interval_seconds` */
    fun alerting(now: Instant): Boolean =
        now.epochSecond - gauge("eia.audit.anchor.last_success")!! >
            AnchorMetrics.STALE_AFTER_INTERVALS * gauge("eia.audit.anchor.interval")!!

    /** 比べるための、最後にアンカーを保存した時刻で判定した場合の条件(使わない方)。 */
    fun publishedStale(now: Instant): Boolean =
        now.epochSecond - gauge("eia.audit.anchor.last_published")!! >
            AnchorMetrics.STALE_AFTER_INTERVALS * gauge("eia.audit.anchor.interval")!!
}

class AnchorMetricsSpec :
    FunSpec({
        fun setUp(block: (MutableClock, FakeAuditDb, InMemoryAnchorStore, AnchorCycle, Gauges) -> Unit) {
            val clock = MutableClock(Instant.parse("2026-10-02T00:00:00Z"))
            val reader = InMemoryMetricReader.create()
            SdkMeterProvider.builder().registerMetricReader(reader).build().use { provider ->
                val metrics = AnchorMetrics(provider.get("anchor-test"), INTERVAL, clock)
                val store = InMemoryAnchorStore(clock)
                val cycle = AnchorCycle(SERVICE, store, AnchorPublisher(SERVICE, store, Duration.ofDays(1), clock), metrics, clock)
                block(clock, FakeAuditDb(), store, cycle, Gauges(reader))
            }
        }

        test("注文がない状態で間隔を何度過ぎても、アラートの条件に当たらない(保存しなくても、検査の成功で時刻を更新する)") {
            setUp { clock, db, store, cycle, gauges ->
                db.appendAll(AuditLog(clock), 3)
                cycle.run(db.connection(autoCommit = true)).getOrNull().shouldBeInstanceOf<AnchorOutcome.Published>()
                val published = clock.instant()

                repeat(6) {
                    // 次の検査の直前(間隔のほぼ 1 倍が経過)でも条件に当たらない
                    gauges.alerting(clock.instant().plus(INTERVAL).minusSeconds(1)) shouldBe false
                    clock.advance(INTERVAL)
                    cycle.run(db.connection(autoCommit = true)).getOrNull() shouldBe AnchorOutcome.Unchanged(3)
                    gauges.alerting(clock.instant()) shouldBe false
                }
                store.versions shouldHaveSize 1
                gauges.gauge("eia.audit.anchor.last_published") shouldBe published.epochSecond.toDouble()
                // 最後に保存した時刻で判定していたら、注文がないだけで誤報になる
                gauges.publishedStale(clock.instant()) shouldBe true
                gauges.checks("published") shouldBe 1
                gauges.checks("unchanged") shouldBe 6
            }
        }

        test("記録が増えたのに保存できない(ストレージに届かない)状態が続くと、間隔の 2 倍を超えたところで条件に当たる") {
            setUp { clock, db, store, cycle, gauges ->
                val log = AuditLog(clock)
                db.appendAll(log, 1)
                cycle.run(db.connection(autoCommit = true))
                log.append(db.connection(), testEvent(2)).getOrNull() shouldNotBe null
                store.failure = AuditStorageUnavailable("S3", "HTTP 503")
                val lastSuccess = clock.instant()
                repeat(2) {
                    clock.advance(INTERVAL)
                    cycle.run(db.connection(autoCommit = true))
                }
                gauges.alerting(lastSuccess.plus(INTERVAL.multipliedBy(2))) shouldBe false
                gauges.alerting(lastSuccess.plus(INTERVAL.multipliedBy(2)).plusSeconds(1)) shouldBe true
                gauges.checks("error") shouldBe 2
            }
        }

        test("改竄の疑いで保存を拒否した回は、成功に数えない") {
            setUp { clock, db, _, cycle, gauges ->
                db.appendAll(AuditLog(clock), 2)
                cycle.run(db.connection(autoCommit = true))
                db.rows.removeAt(1)
                repeat(3) {
                    clock.advance(INTERVAL)
                    cycle.run(db.connection(autoCommit = true)).getOrNull().shouldBeInstanceOf<AnchorOutcome.Rejected>()
                }
                gauges.alerting(clock.instant()) shouldBe true
                gauges.checks("rejected") shouldBe 3
            }
        }

        test("起動してから一度も成功しなくても、起動の時刻から間隔の 2 倍で条件に当たる。保存の時刻は、ストレージの最新の版から復元する") {
            setUp { clock, db, store, cycle, gauges ->
                gauges.gauge("eia.audit.anchor.interval") shouldBe 3600.0
                gauges.gauge("eia.audit.anchor.last_published") shouldBe null
                store.failure = AuditStorageUnavailable("S3", "HTTP 503")
                clock.advance(INTERVAL.multipliedBy(2).plusSeconds(1))
                cycle.run(db.connection(autoCommit = true))
                gauges.alerting(clock.instant()) shouldBe true
            }
            setUp { clock, db, store, _, _ ->
                db.appendAll(AuditLog(clock), 1)
                val reader = InMemoryMetricReader.create()
                SdkMeterProvider.builder().registerMetricReader(reader).build().use { provider ->
                    val first = AnchorCycle(SERVICE, store, AnchorPublisher(SERVICE, store, Duration.ofDays(1), clock), clock = clock)
                    first.run(db.connection(autoCommit = true))
                    val publishedAt = clock.instant()
                    clock.advance(INTERVAL)
                    // 再起動したプロセスのメトリクス
                    val metrics = AnchorMetrics(provider.get("anchor-test"), INTERVAL, clock)
                    AnchorCycle(SERVICE, store, AnchorPublisher(SERVICE, store, Duration.ofDays(1), clock), metrics, clock)
                        .run(db.connection(autoCommit = true))
                        .getOrNull() shouldBe AnchorOutcome.Unchanged(1)
                    Gauges(reader).gauge("eia.audit.anchor.last_published") shouldBe publishedAt.epochSecond.toDouble()
                }
            }
        }
    })
