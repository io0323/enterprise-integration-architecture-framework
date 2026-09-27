package io.eia.platform.security.ktor

import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.isHandled
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import org.slf4j.LoggerFactory

/**
 * スコープによる認可(Framework 12.3)。[build] のルートは、トークンが [scopes] をすべて持つときだけ処理する。
 *
 * - 足りなければ 403 と `WWW-Authenticate: Bearer error="insufficient_scope", scope="<必要なスコープ>"`(RFC 6750 §3.1)。
 *   realm は、認証を通した [eiaJwt] の設定と同じ値を入れる。
 * - `authenticate { }` の中で使う。認証を通っていない(principal がない)場合は 401 にする(`authenticate(optional = true)` の中や、
 *   `authenticate` の外に置いた設定の誤りでも、処理を通さない側に倒す)。
 *
 * ```
 * authenticate { requireScopes("sales.order:write") { post("/v1/orders") { ... } } }
 * ```
 */
public fun Route.requireScopes(
    vararg scopes: String,
    build: Route.() -> Unit,
): Route {
    require(scopes.isNotEmpty()) { "requireScopes にスコープを 1 つ以上指定してください" }
    scopes.forEach(BearerChallenge::requireValidScope)
    val required = scopes.toSet()
    val route = createChild(RequireScopesSelector(required))
    route.install(RequireScopesPlugin) { this.scopes = required }
    route.build()
    return route
}

internal class RequireScopesConfig {
    var scopes: Set<String> = emptySet()
}

private val logger = LoggerFactory.getLogger("io.eia.platform.security.ktor.RequireScopes")

internal val RequireScopesPlugin =
    createRouteScopedPlugin("EiaRequireScopes", ::RequireScopesConfig) {
        val required = pluginConfig.scopes
        on(AuthenticationChecked) { call ->
            // 認証の段で応答済み(401 / 503)なら何もしない
            if (call.isHandled) return@on
            val token = call.verifiedToken()
            val realm = call.attributes.getOrNull(EiaJwtAuthenticationProvider.REALM)
            when {
                token == null -> {
                    // authenticate(optional = true) でトークンがない場合と、authenticate の外に置いた設定の誤り
                    logger.debug("requireScopes の前に認証を通っていません")
                    BearerChallenge.missingToken(call, realm)
                }

                !token.hasScopes(required) -> {
                    logger.debug("スコープが不足しています required={}", required)
                    BearerChallenge.insufficientScope(call, realm, required)
                }
            }
        }
    }

private class RequireScopesSelector(
    private val scopes: Set<String>,
) : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(requireScopes ${scopes.sorted().joinToString(" ")})"
}
