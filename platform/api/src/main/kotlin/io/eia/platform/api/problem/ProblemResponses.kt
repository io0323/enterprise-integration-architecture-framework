package io.eia.platform.api.problem

import io.eia.platform.api.idempotency.HttpSnapshot
import io.eia.platform.observability.context.CorrelationHeaders
import io.eia.platform.observability.context.ObservabilityContext
import io.eia.shared.kernel.DomainError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Duration

/** Problem Details のメディアタイプ(RFC 9457 §3)。 */
public val ProblemJson: ContentType = ContentType("application", "problem+json")

/**
 * [problem] を `application/problem+json` で返す(ADR-0022 §2)。
 *
 * - `correlationId` は、現在の処理の Correlation ID(`ServerObservability` がコルーチンのコンテキストに置いたもの)。
 *   なければ、応答に付いた `X-Correlation-Id`。どちらもなければ付けない。
 * - `Cache-Control: no-store` を付ける(エラーの応答をキャッシュさせない)。
 * - [Problem.retryAfter] があれば `Retry-After`(秒。切り上げ)を付ける。
 */
public suspend fun ApplicationCall.respondProblem(problem: Problem) {
    attributes.put(ResponseWritten, Unit)
    response.header(HttpHeaders.CacheControl, "no-store")
    problem.retryAfter?.let { response.header(HttpHeaders.RetryAfter, retryAfterSeconds(it)) }
    respondText(problemJson(problem, correlationId()), ProblemJson, HttpStatusCode.fromValue(problem.status))
}

/**
 * [problem] を [HttpSnapshot] にする。冪等の処理([io.eia.platform.api.idempotency.IdempotencyHandler])の中で、エラーを
 * 応答として返すときに使う(422 などの 4xx は保存され、再送にも同じ本文を返す)。ヘッダと本文は [respondProblem] と同じ。
 */
public suspend fun ApplicationCall.problemSnapshot(problem: Problem): HttpSnapshot =
    HttpSnapshot(
        problem.status,
        listOfNotNull(
            HttpHeaders.ContentType to ProblemJson.toString(),
            HttpHeaders.CacheControl to "no-store",
            problem.retryAfter?.let { HttpHeaders.RetryAfter to retryAfterSeconds(it) },
        ),
        problemJson(problem, correlationId()).toByteArray(Charsets.UTF_8),
    )

/** [error] を、[installProblemDetails] の写し方(既定は [Problem.of])で Problem Details にして返す。 */
public suspend fun ApplicationCall.respondError(error: DomainError) {
    val mapper = application.attributes.getOrNull(ProblemDetailsConfigKey)?.mapper
    respondProblem(mapper?.invoke(error) ?: Problem.of(error))
}

/**
 * この応答を platform/api の部品([respondProblem]・[io.eia.platform.api.idempotency.respondIdempotently])が書いたことの印
 * (StatusPages の 404 / 405 の処理が上書きしないようにする)。
 */
internal val ResponseWritten: AttributeKey<Unit> = AttributeKey("eia.api.response-written")

internal val ProblemDetailsConfigKey: AttributeKey<ProblemDetailsConfig> = AttributeKey("eia.api.problem-details-config")

private suspend fun ApplicationCall.correlationId(): String? =
    currentCoroutineContext()[ObservabilityContext]?.correlationId?.value
        ?: response.headers[CorrelationHeaders.X_CORRELATION_ID]

/** 項目の順序は契約の `Problem` と同じ(type, title, status, detail, correlationId, errors)。 */
internal fun problemJson(
    problem: Problem,
    correlationId: String?,
): String =
    buildJsonObject {
        put("type", problem.type.uri)
        put("title", problem.type.title)
        put("status", problem.status)
        problem.type.detail?.let { put("detail", it) }
        correlationId?.let { put("correlationId", it) }
        if (problem.errors.isNotEmpty()) {
            putJsonArray("errors") {
                problem.errors.forEach { error ->
                    addJsonObject {
                        put("field", error.field)
                        put("message", error.message)
                    }
                }
            }
        }
    }.toString()

private const val MILLIS_PER_SECOND = 1_000

/** `Retry-After` の秒数(delay-seconds。RFC 9110 §10.2.3)。1 秒未満の端数は切り上げ、負の値は 0 にする。 */
internal fun retryAfterSeconds(duration: Duration): String {
    val millis = duration.inWholeMilliseconds.coerceAtLeast(0)
    return ((millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toString()
}
