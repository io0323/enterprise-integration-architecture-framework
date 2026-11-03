package io.eia.bffgraphql.adapters.graphql

import io.eia.platform.api.problem.Problem
import io.eia.platform.api.problem.ProblemType
import io.eia.platform.api.problem.respondProblem
import io.eia.platform.security.ktor.verifiedToken
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * `POST /v1/graphql`(公開パス `/storefront/v1/graphql`。ADR-0005)。`authenticate { }` の中に置く。
 *
 * - POST だけ。1 つのクエリだけ受け付ける(複数の操作をまとめた配列は受け付けない)。本文の大きさは `@queryLimits` の `maxBodyBytes` まで。
 * - **状態コード**: 実行できなかった(本文・構文・検証の誤り、上限の超過)は 400。実行したら、項目のエラーがあっても 200(部分応答。ADR-0043 §6)。
 *   複雑度の枠が足りなければ 429 + `Retry-After` + Problem Details `rate-limited`(ADR-0043 §9)。
 * - 応答には、クエリの内容を残さない。
 */
public fun Route.storefrontGraphql(engine: StorefrontGraphql) {
    post("/v1/graphql") { call.execute(engine) }
}

private val json = Json { ignoreUnknownKeys = true }

@Serializable
@OptIn(ExperimentalSerializationApi::class)
private data class GraphqlRequest(
    val query: String,
    @SerialName("operationName") val operationName: String? = null,
    val variables: JsonObject? = null,
)

@Suppress("ReturnCount") // 実行できない理由ごとに返す
private suspend fun ApplicationCall.execute(engine: StorefrontGraphql) {
    // authenticate の外に置いた設定の誤りでも、認可なしでは実行しない
    val token = verifiedToken() ?: return respondBadRequest(HttpStatusCode.Unauthorized, "A token is required")
    val body = readBody(engine.limits.maxBodyBytes) ?: return respondBadRequest(message = "The request body is too large")
    val request =
        try {
            json.decodeFromString(GraphqlRequest.serializer(), body.decodeToString())
        } catch (_: SerializationException) {
            return respondBadRequest(message = "The request body is not a GraphQL request")
        } catch (_: IllegalArgumentException) {
            return respondBadRequest(message = "The request body is not a GraphQL request")
        }
    val result =
        engine.execute(
            request.query,
            request.operationName,
            request.variables?.let(GraphqlJson::decodeObject).orEmpty(),
            token,
        )
    RateLimited.retryAfter(result)?.let { wait -> return respondProblem(Problem(ProblemType.RATE_LIMITED, retryAfter = wait)) }
    val status = if (result.isDataPresent) HttpStatusCode.OK else HttpStatusCode.BadRequest
    respondText(GraphqlJson.encode(result.toSpecification()).toString(), ContentType.Application.Json, status)
}

/** 上限を超えたら `null`。`Content-Length` を信じず、読みながら確かめる。 */
private suspend fun ApplicationCall.readBody(maxBytes: Int): ByteArray? {
    val declared = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (declared != null && declared > maxBytes) return null
    val bytes = receiveChannel().readRemaining((maxBytes + 1).toLong()).readByteArray()
    return bytes.takeIf { it.size <= maxBytes }
}

private suspend fun ApplicationCall.respondBadRequest(
    status: HttpStatusCode = HttpStatusCode.BadRequest,
    message: String,
) {
    val body = GraphqlJson.encode(mapOf("errors" to listOf(StorefrontGraphql.badRequest(message))))
    respondText(body.toString(), ContentType.Application.Json, status)
}

/** GraphQL の実行の値(`Map`・`List`・スカラー)と JSON の変換。 */
internal object GraphqlJson {
    fun encode(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is Map<*, *> -> buildJsonObject { value.forEach { (key, item) -> put(key.toString(), encode(item)) } }
            is Iterable<*> -> buildJsonArray { value.forEach { add(encode(it)) } }
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }

    fun decodeObject(value: JsonObject): Map<String, Any?> = value.mapValues { (_, item) -> decode(item) }

    private fun decode(value: JsonElement): Any? =
        when (value) {
            is JsonNull -> {
                null
            }

            is JsonObject -> {
                decodeObject(value)
            }

            is JsonArray -> {
                value.map(::decode)
            }

            is JsonPrimitive -> {
                when {
                    value.isString -> value.content
                    else -> value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull ?: value.content
                }
            }
        }
}
