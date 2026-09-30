package io.eia.platform.api.idempotency

import io.eia.platform.api.problem.Problem
import io.eia.platform.api.problem.ProblemType
import io.eia.platform.api.problem.ResponseWritten
import io.eia.platform.api.problem.respondProblem
import io.eia.shared.kernel.IdempotencyKey
import io.eia.shared.kernel.Result
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes

/** `Idempotency-Key` ヘッダ(INTEGRATION_STANDARDS §2)。 */
public const val IDEMPOTENCY_KEY_HEADER: String = "Idempotency-Key"

/** 再送への応答に付けるヘッダ(値は `true`)。標準のヘッダではない(Stripe などと同じ名前)。ADR-0022 §3。 */
public const val IDEMPOTENT_REPLAYED_HEADER: String = "Idempotent-Replayed"

/**
 * `Idempotency-Key` の付いた要求を、[handler] で冪等に処理して応答する(Framework 5.4。ADR-0022 §3)。
 *
 * - キーがない・複数ある(`,` で連結された値を含む)・形式が不正なら、処理せずに 400 `idempotency-key-missing` を返す。
 * - 本文はここで 1 回だけ読み、指紋の計算と [process] の両方に渡す([process] で `receive` しない)。
 * - 指紋はメソッド・パス(クエリを含む)・本文から計算する([RequestFingerprint])。
 * - 再送には保存した応答を返し、[IDEMPOTENT_REPLAYED_HEADER] を付ける。`X-Correlation-Id` はその要求自身の値になる。
 * - 同じキーで内容の違う要求は 422 `idempotency-key-reused`、処理中は 409 `idempotency-request-in-progress`(`Retry-After`)。
 *
 * 認証と認可(`authenticate` / `requireScopes`)の後で呼ぶ。[clientId] は認証したクライアント(JWT の `azp` など)を渡す。
 *
 * @param transaction 業務の更新の範囲。[process] の更新と応答の保存を同じトランザクションにする
 * @param process 処理の本体。応答を [HttpSnapshot] で返す(エラーは `problemSnapshot` で作る)
 */
public suspend fun ApplicationCall.respondIdempotently(
    handler: IdempotencyHandler,
    clientId: String,
    transaction: TransactionBoundary,
    process: suspend (body: ByteArray) -> HttpSnapshot,
) {
    val key = idempotencyKey() ?: return respondProblem(Problem(ProblemType.IDEMPOTENCY_KEY_MISSING))
    val body = receive<ByteArray>()
    val fingerprint = RequestFingerprint.of(request.httpMethod.value, request.uri, request.headers[HttpHeaders.ContentType], body)
    val request = IdempotencyRequest(IdempotencyScope(clientId, key), fingerprint)
    when (val outcome = handler.execute(request, transaction) { process(body) }) {
        is IdempotencyOutcome.Processed -> {
            respond(outcome.response.status, outcome.response.headers, outcome.response.body)
        }

        is IdempotencyOutcome.Replayed -> {
            response.header(IDEMPOTENT_REPLAYED_HEADER, "true")
            respond(outcome.response.status, outcome.response.headers, outcome.response.body)
        }

        IdempotencyOutcome.KeyReused -> {
            respondProblem(Problem(ProblemType.IDEMPOTENCY_KEY_REUSED))
        }

        is IdempotencyOutcome.InProgress -> {
            respondProblem(Problem(ProblemType.IDEMPOTENCY_REQUEST_IN_PROGRESS, retryAfter = outcome.retryAfter))
        }
    }
}

/**
 * 1 つだけあり、形式が正しいキー。複数あるときは、どれが正しいか決められないため不正とする。
 * 同じ名前のヘッダは `,` で 1 行に連結されうる(RFC 9110 §5.3。クライアントや途中のプロキシが行う)ため、`,` を含む値も複数とみなす。
 */
private fun ApplicationCall.idempotencyKey(): IdempotencyKey? {
    val values =
        request.headers
            .getAll(IDEMPOTENCY_KEY_HEADER)
            .orEmpty()
            .flatMap { it.split(',') }
    if (values.size != 1) return null
    return (IdempotencyKey.parse(values.single().trim()) as? Result.Ok)?.value
}

/** `Content-Type` は Ktor のヘッダに直接入れられないため、応答の型として渡す。`Content-Length` は Ktor が付ける。 */
private suspend fun ApplicationCall.respond(
    status: Int,
    headers: List<Pair<String, String>>,
    body: ByteArray,
) {
    attributes.put(ResponseWritten, Unit)
    var contentType: ContentType? = null
    headers.forEach { (name, value) ->
        when {
            name.equals(HttpHeaders.ContentType, ignoreCase = true) -> contentType = ContentType.parse(value)
            name.equals(HttpHeaders.ContentLength, ignoreCase = true) -> Unit
            else -> response.header(name, value)
        }
    }
    respondBytes(body, contentType, HttpStatusCode.fromValue(status))
}
