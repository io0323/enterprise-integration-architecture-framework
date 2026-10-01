package io.eia.order.app

import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext

/** TLS の接続(API のポート)で受けたリクエストだけに当たるルート。 */
internal fun Route.overTls(build: Route.() -> Unit): Route = createChild(SchemeSelector("https")).apply(build)

/** 平文の接続(ヘルスチェックのポート)で受けたリクエストだけに当たるルート。 */
internal fun Route.overPlaintext(build: Route.() -> Unit): Route = createChild(SchemeSelector("http")).apply(build)

/**
 * 接続の種類で分けるルートの条件。Ktor の Netty(HTTP/1.1)は、パイプラインに TLS の処理があれば `https` を返す。
 * ポートの番号で分けない(テストでは空いているポートを使うため、番号が起動するまで決まらない)。
 */
private data class SchemeSelector(
    val scheme: String,
) : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ): RouteSelectorEvaluation =
        if (context.call.request.local.scheme == scheme) {
            RouteSelectorEvaluation.Transparent
        } else {
            RouteSelectorEvaluation.Failed
        }

    override fun toString(): String = "(scheme:$scheme)"
}
