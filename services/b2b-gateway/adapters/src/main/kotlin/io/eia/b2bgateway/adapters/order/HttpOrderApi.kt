package io.eia.b2bgateway.adapters.order

import io.eia.b2bgateway.application.port.outbound.OrderApi
import io.eia.b2bgateway.application.port.outbound.Placement
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
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * 受け付けた注文を、order の API に Gateway 経由で渡す(`POST /sales/v1/orders`。INT-SALES-001。ADR-0040 §6)。
 *
 * - **認証**: Client Credentials のトークン(クライアント `eiaf-b2b-gateway`。スコープ `sales.order:write` だけ)。401 ならトークンを捨て、
 *   一時的な失敗として取り直す。
 * - **冪等**: 受け付けの時点で固定した本文を、そのまま `Idempotency-Key` を付けて送る(送り直しでも同じバイト列)。order は 24 時間、同じキーに
 *   最初の応答を返す。
 * - **Timeout・Retry・Circuit Breaker**: [resilience](`order-api`。app で作る)の中で呼ぶ(ADR-0021)。408・409(同じキーを処理中)・429・
 *   502〜504・接続の失敗はリトライする。**Fallback** は呼び出し側(送信待ちのまま残し、次の回に送り直す)。
 * - **結果の分け方**: 201 は受け付け。400・422(`validation-failed` など)は業務の誤りとして [Placement.Refused]。
 *   422 の `idempotency-key-reused`(固定した本文と違う要求。ありえないはずの設定の誤り)・403・500 などは Result のエラー。
 *
 * [client] には `ClientObservability` を付ける(traceparent と Correlation ID を送る。Framework 14)。
 */
public class HttpOrderApi(
    private val client: HttpClient,
    private val gatewayUrl: URI,
    private val tokens: ClientCredentialsTokenProvider,
    private val resilience: Resilience,
    private val classifier: HttpCallClassifier = HttpCallClassifier(HttpCallClassifier.DEFAULT_RETRYABLE_STATUSES + CONFLICT),
) : OrderApi {
    override suspend fun place(
        idempotencyKey: String,
        body: ByteArray,
    ): Result<Placement, DomainError> =
        resilience.execute {
            when (val token = tokens.token()) {
                is Result.Err -> {
                    err(token.error.asDomainError())
                }

                is Result.Ok -> {
                    var received: HttpResponse? = null
                    val result =
                        classifier.call(
                            send = {
                                client
                                    .post("${gatewayUrl.toString().trimEnd('/')}$PATH") {
                                        header(HttpHeaders.Authorization, token.value.authorizationHeader())
                                        header(IDEMPOTENCY_KEY, idempotencyKey)
                                        contentType(ContentType.Application.Json)
                                        setBody(body)
                                        expectSuccess = false
                                    }.also { received = it }
                            },
                            read = { response -> created(response) },
                        )
                    val rejected = (result as? Result.Err)?.error as? HttpCallRejected
                    val response = received
                    when {
                        rejected == null || response == null -> {
                            result
                        }

                        rejected.status == UNAUTHORIZED -> {
                            tokens.invalidate(token.value)
                            err(HttpCallUnavailable(UNAUTHORIZED, HttpCallClassifier.REASON_STATUS))
                        }

                        rejected.status == BAD_REQUEST -> {
                            ok(Placement.Refused(BAD_REQUEST))
                        }

                        rejected.status == UNPROCESSABLE -> {
                            unprocessable(response)
                        }

                        else -> {
                            result
                        }
                    }
                }
            }
        }

    /** 201 の本文(契約の `Order`)から、注文の ID を読む。 */
    @Suppress("ReturnCount") // 形式の誤りのそれぞれで返す
    private suspend fun created(response: HttpResponse): Result<Placement, DomainError> {
        val order =
            try {
                json.decodeFromString(OrderJson.serializer(), response.bodyAsText())
            } catch (_: SerializationException) {
                return err(UnexpectedError("注文の登録の応答の形式が契約と違います"))
            } catch (_: IllegalArgumentException) {
                return err(UnexpectedError("注文の登録の応答の形式が契約と違います"))
            }
        return ok(Placement.Accepted(order.id))
    }

    /** 422 の Problem Details の `type`。同じキーの違う要求なら設定の誤り、それ以外は業務の誤り。 */
    private suspend fun unprocessable(response: HttpResponse): Result<Placement, DomainError> {
        val type =
            try {
                json.decodeFromString(ProblemJson.serializer(), response.bodyAsText()).type
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        return if (type?.endsWith(IDEMPOTENCY_KEY_REUSED) == true) err(IdempotencyKeyReused) else ok(Placement.Refused(UNPROCESSABLE))
    }

    @Serializable
    private class OrderJson(
        val id: String,
    )

    @Serializable
    private class ProblemJson(
        val type: String? = null,
    )

    /** 同じ Idempotency-Key で、違う本文を送った(固定した本文を書き換えた・キーの作り方の誤り)。送り直しても直らないので、運用者が調べる。 */
    public data object IdempotencyKeyReused : DomainError.NonRetryable {
        override val code: String get() = "idempotency_key_reused"
        override val message: String get() = "order の API が、同じ Idempotency-Key の違う要求として拒否しました"
    }

    public companion object {
        /** API Gateway の公開のパス(ADR-0005)。order-service の `/v1/orders` に転送される。 */
        public const val PATH: String = "/sales/v1/orders"
        public const val SCOPE: String = "sales.order:write"
        private const val IDEMPOTENCY_KEY = "Idempotency-Key"
        private const val IDEMPOTENCY_KEY_REUSED = "/idempotency-key-reused"
        private const val BAD_REQUEST = 400
        private const val UNAUTHORIZED = 401
        private const val CONFLICT = 409
        private const val UNPROCESSABLE = 422
        private val json = Json { ignoreUnknownKeys = true }
    }
}
