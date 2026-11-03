package io.eia.bffgraphql.adapters.outbound

import io.eia.bffgraphql.application.model.AddressView
import io.eia.bffgraphql.application.model.MoneyView
import io.eia.bffgraphql.application.model.OrderLineView
import io.eia.bffgraphql.application.model.OrderStatusView
import io.eia.bffgraphql.application.model.OrderView
import io.eia.bffgraphql.application.port.outbound.OrderQueries
import io.eia.platform.security.token.ClientCredentialsTokenProvider
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.Resilience
import io.eia.shared.sdk.http.HttpCallClassifier
import io.eia.shared.sdk.http.HttpCallRejected
import io.eia.shared.sdk.http.HttpCallUnavailable
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * 注文を、order の API から Gateway 経由でまとめて読む(`GET /sales/v1/orders?ids=`。INT-SALES-001。ADR-0043 §9)。
 *
 * - **認証**: Client Credentials のトークン(クライアント `eiaf-bff-graphql`。読み取りのスコープだけ。ADR-0043 §7)。401 ならトークンを捨て、
 *   一時的な失敗として取り直す。
 * - **Timeout・Retry・Circuit Breaker**: [resilience](`order-api`。app で `ResilienceMetrics` から作る)の中で呼ぶ(ADR-0021)。
 *   **Fallback** は BFF の部分応答(ADR-0043 §6)。
 * - 応答の形は契約の `OrderBatch`。知らない項目は読み飛ばし、一覧にない状態は `UNKNOWN` にする(Tolerant Reader)。
 *
 * [client] には `ClientObservability` を付ける(traceparent と Correlation ID を送る。Framework 14)。
 */
public class HttpOrderQueries(
    private val client: HttpClient,
    private val gatewayUrl: URI,
    private val tokens: ClientCredentialsTokenProvider,
    private val resilience: Resilience,
    private val quota: OrderQuotaMetrics,
    private val classifier: HttpCallClassifier = HttpCallClassifier(),
) : OrderQueries {
    override suspend fun find(ids: Set<String>): Result<List<OrderView>, DomainError> =
        resilience.execute {
            when (val token = tokens.token()) {
                is Result.Err -> {
                    err(token.error.asDomainError())
                }

                is Result.Ok -> {
                    val result =
                        classifier.call(
                            send = {
                                client
                                    .get("${gatewayUrl.toString().trimEnd('/')}$PATH") {
                                        ids.forEach { parameter(IDS, it) }
                                        header(HttpHeaders.Authorization, token.value.authorizationHeader())
                                        expectSuccess = false
                                    }.also(quota::observe)
                            },
                            read = ::orders,
                        )
                    if ((result as? Result.Err)?.error.let { it is HttpCallRejected && it.status == UNAUTHORIZED }) {
                        tokens.invalidate(token.value)
                        err(HttpCallUnavailable(UNAUTHORIZED, HttpCallClassifier.REASON_STATUS))
                    } else {
                        result
                    }
                }
            }
        }

    private suspend fun orders(response: HttpResponse): Result<List<OrderView>, DomainError> =
        try {
            ok(json.decodeFromString(OrderBatchJson.serializer(), response.bodyAsText()).orders.map(OrderJson::toView))
        } catch (_: SerializationException) {
            err(UnexpectedError("注文の応答の形式が契約と違います"))
        } catch (_: IllegalArgumentException) {
            err(UnexpectedError("注文の応答の形式が契約と違います"))
        }

    @Serializable
    private class OrderBatchJson(
        val orders: List<OrderJson>,
    )

    @Serializable
    @Suppress("LongParameterList") // 契約の `Order` の項目
    private class OrderJson(
        val id: String,
        val customerId: String,
        val status: String,
        val orderedAt: String,
        val lines: List<OrderLineJson>,
        val totalAmount: MoneyJson,
        val shippingAddress: AddressJson,
    ) {
        fun toView(): OrderView =
            OrderView(
                id = id,
                customerId = customerId,
                status = OrderStatusView.entries.firstOrNull { it.name == status } ?: OrderStatusView.UNKNOWN,
                orderedAt = orderedAt,
                lines = lines.map(OrderLineJson::toView),
                totalAmount = totalAmount.toView(),
                shippingAddress = shippingAddress.toView(),
            )
    }

    @Serializable
    private class OrderLineJson(
        val lineNumber: Int,
        val productId: String,
        val sku: String,
        val quantity: Long,
        val unitPrice: MoneyJson,
        val lineAmount: MoneyJson,
    ) {
        fun toView(): OrderLineView = OrderLineView(lineNumber, productId, sku, quantity, unitPrice.toView(), lineAmount.toView())
    }

    @Serializable
    private class MoneyJson(
        val amount: String,
        val currency: String,
    ) {
        fun toView(): MoneyView = MoneyView(amount, currency)
    }

    @Serializable
    private class AddressJson(
        val countryCode: String,
        val postalCode: String,
        val region: String? = null,
        val city: String,
        val line1: String,
        val line2: String? = null,
    ) {
        fun toView(): AddressView = AddressView(countryCode, postalCode, region, city, line1, line2)
    }

    public companion object {
        /** API Gateway の公開のパス(ADR-0005)。order-service の `/v1/orders` に転送される。 */
        public const val PATH: String = "/sales/v1/orders"
        public const val SCOPE: String = "sales.order:read"
        private const val IDS = "ids"
        private const val UNAUTHORIZED = 401
        private val json = Json { ignoreUnknownKeys = true }
    }
}
