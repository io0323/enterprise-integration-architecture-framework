package io.eia.batchetl.adapters.http

import io.eia.batchetl.application.port.outbound.OrderSummarySource
import io.eia.batchetl.domain.BusinessDay
import io.eia.batchetl.domain.DailySummary
import io.eia.platform.reliability.HttpCallClassifier
import io.eia.platform.reliability.HttpCallRejected
import io.eia.platform.reliability.HttpCallUnavailable
import io.eia.platform.security.token.ClientCredentialsTokenProvider
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.Resilience
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * order-service の日次の集計を、API Gateway 経由で読む(`GET /sales/v1/order-summaries/daily/{date}`。INT-SALES-001。ADR-0031 §3・§6)。
 *
 * - **認証**: Client Credentials のトークン(クライアント `eiaf-batch-etl`。スコープ `sales.order:report` だけ)。401 ならトークンを捨て、
 *   一時的な失敗として取り直す(IdP 側で失効させられた場合)。
 * - **Timeout・Retry・Circuit Breaker**: [resilience] の中で呼ぶ(ADR-0021)。408・429・502〜504・接続の失敗はリトライし、
 *   ほかの 4xx・500 はリトライしない。**Fallback** は呼び出し側(その日を UNVERIFIED にして、次の再実行に延ばす)。
 * - **区切りの確認**: 応答の `timeZone` が分析の区切り(Asia/Tokyo。ADR-0031 §1)と違えば、比べずに失敗にする(区切りがずれた集計を比べない)。
 *
 * [client] には `ClientObservability` を付ける(traceparent と Correlation ID を送る。Framework 14)。
 */
public class HttpOrderSummarySource(
    private val client: HttpClient,
    private val gatewayUrl: URI,
    private val tokens: ClientCredentialsTokenProvider,
    private val resilience: Resilience,
    private val classifier: HttpCallClassifier = HttpCallClassifier(),
) : OrderSummarySource {
    override suspend fun summary(day: BusinessDay): Result<DailySummary, DomainError> =
        resilience.execute {
            when (val token = tokens.token()) {
                is Result.Err -> {
                    err(token.error.asDomainError())
                }

                is Result.Ok -> {
                    val result =
                        classifier.call(
                            send = {
                                client.get("${gatewayUrl.toString().trimEnd('/')}$PATH$day") {
                                    header(HttpHeaders.Authorization, token.value.authorizationHeader())
                                    expectSuccess = false
                                }
                            },
                            read = { response -> read(day, response) },
                        )
                    if (result is Result.Err && (result.error as? HttpCallRejected)?.status == UNAUTHORIZED) {
                        tokens.invalidate(token.value)
                        err(HttpCallUnavailable(UNAUTHORIZED, HttpCallClassifier.REASON_STATUS))
                    } else {
                        result
                    }
                }
            }
        }

    @Suppress("ReturnCount") // 形式・区切りの誤りのそれぞれで返す
    private suspend fun read(
        day: BusinessDay,
        response: HttpResponse,
    ): Result<DailySummary, DomainError> {
        val body =
            try {
                json.decodeFromString(SummaryJson.serializer(), response.bodyAsText())
            } catch (_: SerializationException) {
                return err(UnexpectedError("日次の集計の応答の形式が契約と違います"))
            } catch (_: IllegalArgumentException) {
                return err(UnexpectedError("日次の集計の応答の形式が契約と違います"))
            }
        return when {
            body.timeZone != BusinessDay.TIME_ZONE_ID -> err(UnexpectedError("日次の集計の区切り(${body.timeZone})が分析の区切りと違います"))
            body.date != day.toString() -> err(UnexpectedError("日次の集計の日付が問い合わせと違います"))
            body.orderCount < 0 || !DIGEST.matches(body.digest) -> err(UnexpectedError("日次の集計の件数かハッシュの形式が契約と違います"))
            else -> ok(DailySummary(day, body.orderCount, body.digest))
        }
    }

    /** 契約の `DailyOrderSummary`(contracts/openapi/order-api.v1.yaml)。比べるのに使う項目だけを読む。 */
    @Serializable
    private class SummaryJson(
        val date: String,
        val timeZone: String,
        val orderCount: Long,
        val digest: String,
    )

    public companion object {
        /** API Gateway の公開のパス(ADR-0005)。order-service の `/v1/order-summaries/daily/{date}` に転送される。 */
        public const val PATH: String = "/sales/v1/order-summaries/daily/"
        public const val SCOPE: String = "sales.order:report"
        private const val UNAUTHORIZED = 401
        private val DIGEST = Regex("^[0-9a-f]{64}$")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
