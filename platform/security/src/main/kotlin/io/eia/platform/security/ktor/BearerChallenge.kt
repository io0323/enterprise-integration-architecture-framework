package io.eia.platform.security.ktor

import io.eia.platform.api.problem.Problem
import io.eia.platform.api.problem.ProblemType
import io.eia.platform.api.problem.respondProblem
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header

/**
 * 401 / 403 / 503 の応答(RFC 6750 §3・RFC 9457。ADR-0019 §5)。
 *
 * - 本文は `platform/api` の Problem Details(`unauthorized` / `forbidden` / `service-unavailable`。ADR-0022 §2)。
 *   拒否した理由(期限切れ・aud の不一致など)は返さない。理由を返すと、攻撃者がトークンを作り直す手がかりになるため。
 *   理由はログとメトリクスで追う。`Cache-Control: no-store` と `correlationId` は `respondProblem` が付ける。
 * - `WWW-Authenticate` の値に入れるのは、設定の realm と、規則(RFC 6749 の scope-token)で検証済みのスコープだけ。
 *   リクエストから受け取った値は入れない(ヘッダの注入を防ぐ)。
 */
internal object BearerChallenge {
    suspend fun missingToken(
        call: ApplicationCall,
        realm: String?,
    ) = respond(call, ProblemType.UNAUTHORIZED, header(realm))

    suspend fun invalidToken(
        call: ApplicationCall,
        realm: String?,
    ) = respond(call, ProblemType.UNAUTHORIZED, header(realm, "error=\"invalid_token\""))

    suspend fun insufficientScope(
        call: ApplicationCall,
        realm: String?,
        required: Set<String>,
    ) = respond(
        call,
        ProblemType.FORBIDDEN,
        header(realm, "error=\"insufficient_scope\"", "scope=\"${required.sorted().joinToString(" ")}\""),
    )

    /** 公開鍵を取得できず、トークンを検証できない。クライアントのトークンの問題ではないため 401 にしない。 */
    suspend fun keysUnavailable(call: ApplicationCall) = respond(call, ProblemType.SERVICE_UNAVAILABLE, wwwAuthenticate = null)

    private fun header(
        realm: String?,
        vararg params: String,
    ): String {
        val all = listOfNotNull(realm?.let { "realm=\"$it\"" }) + params
        return if (all.isEmpty()) SCHEME else "$SCHEME ${all.joinToString(", ")}"
    }

    private suspend fun respond(
        call: ApplicationCall,
        type: ProblemType,
        wwwAuthenticate: String?,
    ) {
        wwwAuthenticate?.let { call.response.header(HttpHeaders.WWWAuthenticate, it) }
        // 認証の失敗をキャッシュさせない(RFC 6750 §5.3 に倣う)。no-store は respondProblem が付ける
        call.respondProblem(Problem(type))
    }

    private const val SCHEME = "Bearer"

    /** realm は quoted-string に入れるため、`"` と `\` と制御文字を含まない印字可能な ASCII に限る。 */
    private val REALM = Regex("[\\x20\\x21\\x23-\\x5B\\x5D-\\x7E]+")

    fun requireValidRealm(realm: String) {
        require(REALM.matches(realm)) { "realm に使えない文字が含まれています" }
    }
}
