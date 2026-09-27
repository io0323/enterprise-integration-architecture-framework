package io.eia.platform.security.ktor

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText

/**
 * 401 / 403 / 503 の応答(RFC 6750 §3・RFC 9457。ADR-0019 §5)。
 *
 * - 本文は Problem Details の最小形(`type` / `title` / `status`)。拒否した理由(期限切れ・aud の不一致など)は返さない。
 *   理由を返すと、攻撃者がトークンを作り直す手がかりになるため。理由はログとメトリクスで追う。
 * - `WWW-Authenticate` の値に入れるのは、設定の realm と、規則(RFC 6749 の scope-token)で検証済みのスコープだけ。
 *   リクエストから受け取った値は入れない(ヘッダの注入を防ぐ)。
 */
internal object BearerChallenge {
    suspend fun missingToken(
        call: ApplicationCall,
        realm: String?,
    ) = respond(call, HttpStatusCode.Unauthorized, header(realm))

    suspend fun invalidToken(
        call: ApplicationCall,
        realm: String?,
    ) = respond(call, HttpStatusCode.Unauthorized, header(realm, "error=\"invalid_token\""))

    suspend fun insufficientScope(
        call: ApplicationCall,
        realm: String?,
        required: Set<String>,
    ) = respond(
        call,
        HttpStatusCode.Forbidden,
        header(realm, "error=\"insufficient_scope\"", "scope=\"${required.sorted().joinToString(" ")}\""),
    )

    /** 公開鍵を取得できず、トークンを検証できない。クライアントのトークンの問題ではないため 401 にしない。 */
    suspend fun keysUnavailable(call: ApplicationCall) = respond(call, HttpStatusCode.ServiceUnavailable, wwwAuthenticate = null)

    private fun header(
        realm: String?,
        vararg params: String,
    ): String {
        val all = listOfNotNull(realm?.let { "realm=\"$it\"" }) + params
        return if (all.isEmpty()) SCHEME else "$SCHEME ${all.joinToString(", ")}"
    }

    private suspend fun respond(
        call: ApplicationCall,
        status: HttpStatusCode,
        wwwAuthenticate: String?,
    ) {
        wwwAuthenticate?.let { call.response.header(HttpHeaders.WWWAuthenticate, it) }
        // 認証の失敗をキャッシュさせない(RFC 6750 §5.3 に倣う)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(
            """{"type":"about:blank","title":"${status.description}","status":${status.value}}""",
            PROBLEM_JSON,
            status,
        )
    }

    private const val SCHEME = "Bearer"
    private val PROBLEM_JSON = ContentType("application", "problem+json")

    /** RFC 6749 §3.3 の scope-token(`%x21 / %x23-5B / %x5D-7E`)。`"` と `\` と空白を含まない。 */
    private val SCOPE_TOKEN = Regex("[\\x21\\x23-\\x5B\\x5D-\\x7E]+")

    /** realm は quoted-string に入れるため、`"` と `\` と制御文字を含まない印字可能な ASCII に限る。 */
    private val REALM = Regex("[\\x20\\x21\\x23-\\x5B\\x5D-\\x7E]+")

    fun requireValidScope(scope: String) {
        require(SCOPE_TOKEN.matches(scope)) { "スコープの形式が不正です(RFC 6749 §3.3 の scope-token): $scope" }
    }

    fun requireValidRealm(realm: String) {
        require(REALM.matches(realm)) { "realm に使えない文字が含まれています" }
    }
}
