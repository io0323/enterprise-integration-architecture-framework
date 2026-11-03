package io.eia.bffgraphql.adapters.graphql

import io.eia.bffgraphql.application.port.inbound.LoadOrdersUseCase
import io.eia.bffgraphql.application.port.inbound.LoadShipmentsUseCase
import io.eia.bffgraphql.application.port.inbound.LoadStockLevelsUseCase
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.resilience.CallDeadline
import io.eia.shared.resilience.withCallDeadline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import kotlinx.coroutines.withTimeoutOrNull
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.dataloader.DataLoaderRegistry
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * リクエストの予算の配り方(ADR-0043 §6)。
 *
 * - 注文を先に読む(在庫と出荷は注文の結果で決まる)。注文には [orderSlice] までしか使わせない。
 * - 在庫と出荷は並列に読み、残り時間から [reserve] を引いた時間まで使える。
 * - [reserve] は応答を組み立てる時間。下流の打ち切りを全体の予算切れより先にして、遅いサービスがあっても 503 にせず部分応答を返す。
 */
public data class UpstreamBudget(
    val total: Duration = 5.seconds,
    val orderSlice: Duration = 2.seconds,
    val reserve: Duration = 300.milliseconds,
) {
    init {
        require(orderSlice.isPositive() && reserve.isPositive()) { "予算は正の値です" }
        require(orderSlice + reserve < total) { "注文の上限と余裕の合計は、全体の予算より短くしてください" }
    }
}

/** 下流を読めなかった。[DataFetcherErrors] が errors の 1 件にする。 */
internal class UpstreamFailure(
    val service: String,
    val retryable: Boolean,
) : RuntimeException(null, null, false, false)

/** リクエストごとの DataLoader(同じレベルの読み取りを 1 回にまとめ、重複を除く。ADR-0043 §5)。 */
internal class Upstreams(
    private val orders: LoadOrdersUseCase,
    private val stock: LoadStockLevelsUseCase,
    private val shipments: LoadShipmentsUseCase,
    private val budget: UpstreamBudget,
) {
    fun registry(scope: CoroutineScope): DataLoaderRegistry =
        DataLoaderRegistry
            .newRegistry()
            .register(ORDERS, loader(scope, ORDERS, budget.orderSlice) { orders(it) })
            .register(STOCK, loader(scope, STOCK, null) { stock(it) })
            .register(SHIPMENTS, loader(scope, SHIPMENTS, null) { shipments(it) })
            .build()

    private fun <V> loader(
        scope: CoroutineScope,
        service: String,
        slice: Duration?,
        read: suspend (Set<String>) -> Result<Map<String, V>, DomainError>,
    ): DataLoader<String, V?> =
        DataLoaderFactory.newDataLoader { keys: List<String> ->
            scope.future {
                val found = withinBudget(service, slice) { read(keys.toSet()) }
                keys.map { found[it] }
            }
        }

    /** [slice] と、残り時間から余裕を引いた時間の短い方で打ち切る。読めなければ [UpstreamFailure]。 */
    @Suppress("ThrowsCount") // 読めない理由ごと。DataFetcherErrors が errors の 1 件にする
    private suspend fun <V> withinBudget(
        service: String,
        slice: Duration?,
        read: suspend () -> Result<Map<String, V>, DomainError>,
    ): Map<String, V> {
        val remaining = (CallDeadline.current()?.remaining() ?: budget.total) - budget.reserve
        val allowed = if (slice == null) remaining else minOf(slice, remaining)
        if (!allowed.isPositive()) throw UpstreamFailure(service, retryable = true)
        val result = withCallDeadline(allowed) { withTimeoutOrNull(allowed) { read() } }
        return when (result) {
            null -> throw UpstreamFailure(service, retryable = true)
            is Result.Ok -> result.value
            is Result.Err -> throw UpstreamFailure(service, result.error is DomainError.Retryable)
        }
    }

    companion object {
        const val ORDERS = "orders"
        const val STOCK = "inventory"
        const val SHIPMENTS = "shipping"
    }
}
