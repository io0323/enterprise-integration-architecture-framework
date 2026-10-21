package io.eia.saasmock.app

import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.Result
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val BEARER = "Bearer "

/** トークンで認証する。通らなければ応答して false。 */
internal suspend fun ApplicationCall.authorize(
    mock: SaasMock,
    name: SecretName,
): Boolean {
    val denied = denial(mock, name) ?: return true
    error(denied, if (denied == HttpStatusCode.Unauthorized) "unauthorized" else "unavailable")
    return false
}

/** トークンは使うたびに読む(差し替えに追従する)。読めなければ 503(呼び出し側に再試行させる)。 */
private fun ApplicationCall.denial(
    mock: SaasMock,
    name: SecretName,
): HttpStatusCode? {
    val expected = (mock.secrets.get(name) as? Result.Ok)?.value ?: return HttpStatusCode.ServiceUnavailable
    val provided = request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith(BEARER) }?.removePrefix(BEARER)
    return if (provided.isNullOrEmpty() || Secret(provided) != expected) HttpStatusCode.Unauthorized else null
}

internal suspend fun <T> ApplicationCall.decode(serializer: KSerializer<T>): T? =
    try {
        CrmJson.decodeFromString(serializer, receiveText())
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

internal suspend fun ApplicationCall.respondJson(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(body, ContentType.Application.Json, status)

internal suspend fun ApplicationCall.badRequest(field: String) = error(HttpStatusCode.BadRequest, "invalid_request", "$field が不正です")

/** エラーの本文(契約の Error。`error` と `message`)。値は入れない。 */
internal suspend fun ApplicationCall.error(
    status: HttpStatusCode,
    code: String,
    message: String = status.description,
) = respondJson(
    CrmJson.encodeToString(
        buildJsonObject {
            put("error", code)
            put("message", message)
        },
    ),
    status,
)
