package io.eia.b2bgateway.app

import io.eia.b2bgateway.application.port.inbound.PollInboxUseCase
import io.eia.b2bgateway.application.port.inbound.PollSummary
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import kotlinx.coroutines.CancellationException
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.time.Duration.Companion.milliseconds

class B2bGatewayJobsSpec :
    FunSpec({
        test("想定していない例外が出ても、繰り返しは止まらず次の回に進む") {
            var runs = 0
            shouldThrow<CancellationException> {
                B2bGatewayJobs.repeatEvery("test", 1.milliseconds) {
                    runs++
                    when (runs) {
                        1 -> throw UnsupportedOperationException("壊れた入力")
                        else -> throw CancellationException("テストの終わり")
                    }
                }
            }
            runs shouldBe 2
        }

        test("ある取引先の見回りが想定していない例外で終わっても、ほかの取引先は見回り、失敗を数える") {
            val reader = InMemoryMetricReader.create()
            val meter =
                SdkMeterProvider
                    .builder()
                    .registerMetricReader(reader)
                    .build()
                    .get("test")
            val partners = EdiPartners(listOf("broken", "healthy"), emptyList())
            val polled = mutableListOf<String>()
            val koin =
                koinApplication {
                    modules(
                        module {
                            single { partners }
                            single { B2bGatewayMetrics(meter, partners) }
                            single<PollInboxUseCase> {
                                object : PollInboxUseCase {
                                    override suspend fun invoke(partnerId: String): Result<PollSummary, DomainError> {
                                        polled += partnerId
                                        if (partnerId == "broken") throw UnsupportedOperationException("壊れた入力")
                                        return ok(PollSummary(emptyList(), emptyList()))
                                    }
                                }
                            }
                        },
                    )
                }.koin

            B2bGatewayJobs.pollOnce(koin)

            polled shouldBe listOf("broken", "healthy")
            // 値の入ったカウンタは、壊れた取引先の crash だけ(0 で作った系列を除く)
            val failures =
                reader
                    .collectAllMetrics()
                    .single { it.name == "eia.b2b.inbox.failures" }
                    .longSumData
                    .points
                    .filter { it.value > 0 }
                    .map { point ->
                        point.attributes.get(AttributeKey.stringKey("trading_partner_id")) to
                            point.attributes.get(AttributeKey.stringKey("b2b.reason"))
                    }
            failures shouldBe listOf("broken" to "crash")
        }
    })
