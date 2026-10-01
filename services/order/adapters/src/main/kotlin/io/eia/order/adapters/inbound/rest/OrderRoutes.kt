package io.eia.order.adapters.inbound.rest

import io.eia.order.domain.OrderDraft
import io.eia.order.domain.OrderId
import io.eia.platform.api.idempotency.HttpSnapshot
import io.eia.platform.api.idempotency.respondIdempotently
import io.eia.platform.api.problem.Problem
import io.eia.platform.api.problem.ProblemType
import io.eia.platform.api.problem.problemSnapshot
import io.eia.platform.api.problem.respondError
import io.eia.platform.api.problem.respondProblem
import io.eia.platform.security.ktor.requireScopes
import io.eia.platform.security.ktor.verifiedToken
import io.eia.shared.kernel.NotFoundError
import io.eia.shared.kernel.Result
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

public const val SCOPE_READ: String = "sales.order:read"
public const val SCOPE_WRITE: String = "sales.order:write"

/**
 * 注文の API(契約 contracts/openapi/order-api.v1.yaml。サービス内部のパス `/v1/...`。ADR-0005)。
 * `authenticate { }`(platform/security の `eiaJwt`)の中に置く。JWT の検証は `requireClientId = true` で行う(呼び出し元のクライアントで
 * Idempotency-Key の範囲を分けるため。ADR-0022 §3)。
 *
 * - `POST /v1/orders`(`sales.order:write`): `Idempotency-Key` 必須。`respondIdempotently` で、注文の保存と応答の保存を同じ
 *   トランザクションにする。本文の構造が不正なら 400、値域・業務整合の違反は 422(項目のパスは契約と同じ)。201 の `Location` は
 *   相対参照 `orders/{id}`。
 * - `GET /v1/orders/{orderId}`(`sales.order:read`): ないときと、ID の形式が不正なとき(存在しえない)は 404。
 */
public fun Route.orderRoutes(api: OrderApi) {
    requireScopes(SCOPE_WRITE) {
        post("/v1/orders") { call.placeOrder(api) }
    }
    requireScopes(SCOPE_READ) {
        get("/v1/orders/{orderId}") { call.getOrder(api) }
    }
}

private val json = Json { ignoreUnknownKeys = true } // Tolerant Reader(Framework 5.3)

private suspend fun ApplicationCall.placeOrder(api: OrderApi) {
    val clientId = verifiedToken()?.clientId
    if (clientId.isNullOrBlank()) {
        // eiaJwt を requireClientId = true で設定していれば起こらない。設定の誤りでも、範囲の分からない冪等の処理はしない
        response.header(HttpHeaders.WWWAuthenticate, "Bearer error=\"invalid_token\"")
        return respondProblem(Problem(ProblemType.UNAUTHORIZED))
    }
    respondIdempotently(api.idempotency, clientId, api.transaction) { body -> process(api, body) }
}

/**
 * 冪等の処理の本体。応答を [HttpSnapshot] で返す(4xx は保存され、5xx は保存されずに注文の保存も取り消される)。
 * 本文の構造の不正は 400、金額と domain の違反は 422。
 */
private suspend fun ApplicationCall.process(
    api: OrderApi,
    body: ByteArray,
): HttpSnapshot {
    val request = decode(body) ?: return problemSnapshot(Problem(ProblemType.BAD_REQUEST))
    return when (val mapped = OrderDtoMapper.toDraft(request, api.currencies)) {
        is Result.Ok -> placed(api, mapped.value)
        is Result.Err -> problemSnapshot(Problem.of(mapped.error))
    }
}

/** 本文の構造が不正なら `null`。本文の断片を含みうるため、例外のメッセージは使わない(ADR-0022 §2)。 */
private fun decode(body: ByteArray): PlaceOrderRequestDto? =
    try {
        json.decodeFromString(PlaceOrderRequestDto.serializer(), body.decodeToString())
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private suspend fun ApplicationCall.placed(
    api: OrderApi,
    draft: OrderDraft,
): HttpSnapshot =
    when (val placed = api.placeOrder(draft)) {
        is Result.Ok -> {
            HttpSnapshot(
                HttpStatusCode.Created.value,
                listOf(
                    HttpHeaders.ContentType to ContentType.Application.Json.toString(),
                    HttpHeaders.Location to "orders/${placed.value.id}",
                ),
                json.encodeToString(OrderDto.serializer(), OrderDtoMapper.toDto(placed.value)).toByteArray(Charsets.UTF_8),
            )
        }

        is Result.Err -> {
            problemSnapshot(OrderProblems.of(placed.error))
        }
    }

private suspend fun ApplicationCall.getOrder(api: OrderApi) {
    val rawId = parameters["orderId"].orEmpty()
    val id = (OrderId.parse(rawId) as? Result.Ok)?.value ?: return respondError(NotFoundError("order", "invalid"))
    when (val found = api.getOrder(id)) {
        is Result.Ok -> {
            respondText(
                json.encodeToString(OrderDto.serializer(), OrderDtoMapper.toDto(found.value)),
                ContentType.Application.Json,
            )
        }

        is Result.Err -> {
            respondError(found.error)
        }
    }
}
