package io.eia.integrationflow.adapters.out.saas

import io.eia.integrationflow.adapters.flow.JsonRecords
import io.eia.integrationflow.application.port.outbound.SaasConnector
import io.eia.integrationflow.application.port.outbound.SourcePage
import io.eia.platform.security.secret.SecretName
import io.eia.platform.security.secret.SecretProvider
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.Resilience
import io.eia.shared.sdk.http.HttpCallClassifier
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import kotlin.time.Instant

/**
 * CRM の顧客の API の Connector(外部の契約 contracts/external/crm-api.v1.yaml。INT-SALES-006。ADR-0035 §6)。
 *
 * - `GET /v1/customers?limit=&updated_since=&cursor=` をページごとに呼ぶ。API トークン([CrmApi.token])は呼ぶたびに SecretProvider から読む。
 * - **Timeout・Retry・Circuit Breaker** は依存先ごとに 1 つの [resilience](名前 `saas-crm`。ResilienceMetrics から作る)。
 *   **429 と `Retry-After`** は [HttpCallClassifier] が Retryable の `retryAfter` に写し、その時間だけ待ってから再試行する
 *   (`eia.resilience.retries` で数える。#8-c)。401 などの 4xx はリトライしない。
 * - 応答は **Tolerant Reader** で読む: 使うのは `data` と `next_cursor` だけで、ほかの項目と、レコードの未知の項目は無視する
 *   (レコードはそのまま Flow の mapping に渡し、mapping に書いた項目だけを使う)。
 */
public class CrmCustomersConnector(
    private val client: HttpClient,
    private val api: CrmApi,
    private val secrets: SecretProvider,
    private val resilience: Resilience,
    private val classifier: HttpCallClassifier = HttpCallClassifier(),
) : SaasConnector {
    override val connector: String = CONNECTOR
    override val sourceSystem: String = SOURCE_SYSTEM
    override val resources: Set<String> = setOf(CUSTOMERS)

    /** CRM の API の接続先・API トークンの名前・1 ページの件数。 */
    public data class CrmApi(
        val baseUrl: URI,
        val token: SecretName,
        val pageSize: Int = DEFAULT_PAGE_SIZE,
    ) {
        init {
            require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize は 1〜$MAX_PAGE_SIZE です" }
        }
    }

    override suspend fun page(
        resource: String,
        updatedSince: Instant?,
        cursor: String?,
    ): Result<SourcePage, DomainError> {
        if (resource != CUSTOMERS) return err(UnexpectedError("$CONNECTOR は $resource を読めません"))
        return resilience.execute {
            when (val secret = secrets.get(api.token)) {
                is Result.Err -> {
                    err(UnavailableError("CRM の API トークンを読めません(${secret.error.code})"))
                }

                is Result.Ok -> {
                    classifier.call(
                        send = {
                            client.get("${api.baseUrl.toString().trimEnd('/')}/v1/customers") {
                                header(HttpHeaders.Authorization, "Bearer ${secret.value.reveal()}")
                                parameter("limit", api.pageSize)
                                updatedSince?.let { parameter("updated_since", it.toString()) }
                                cursor?.let { parameter("cursor", it) }
                                expectSuccess = false
                            }
                        },
                        read = ::read,
                    )
                }
            }
        }
    }

    /** `data`(オブジェクトの配列)と `next_cursor`(文字列か null)だけを読む。形が契約と違えば NonRetryable。 */
    private suspend fun read(response: HttpResponse): Result<SourcePage, DomainError> {
        val body =
            try {
                Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        val data = (body?.get("data") as? JsonArray)?.takeIf { items -> items.all { it is JsonObject } }
        val next = body?.get("next_cursor")
        val cursor = (next as? JsonPrimitive)?.takeIf { it.isString }?.content
        val cursorReadable = next == null || next == JsonNull || cursor != null
        return if (data == null || !cursorReadable) {
            err(UnexpectedError("CRM の顧客の一覧の応答の形式が契約と違います"))
        } else {
            ok(SourcePage(data.map(JsonRecords::of), cursor))
        }
    }

    public companion object {
        public const val CONNECTOR: String = "INT-SALES-006"
        public const val SOURCE_SYSTEM: String = "crm"
        public const val CUSTOMERS: String = "customers"

        /** 依存先の名前(ResilienceMetrics の `eia.dependency.name`)。 */
        public const val RESILIENCE_NAME: String = "saas-crm"
        public const val DEFAULT_PAGE_SIZE: Int = 100
        public const val MAX_PAGE_SIZE: Int = 100
    }
}
