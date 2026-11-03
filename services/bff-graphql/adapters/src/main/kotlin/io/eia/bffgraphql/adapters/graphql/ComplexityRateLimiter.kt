package io.eia.bffgraphql.adapters.graphql

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 複雑度のトークンバケット(クライアントごと。ADR-0043 §9)。Gateway の Rate Limit はリクエストの数しか見ないので、重いクエリを続ける
 * クライアントをここで止める。キーは検証したトークンの `azp` なので、偽のヘッダで他人の枠を消費できない。クライアントの数は Keycloak の登録の数で決まる。
 *
 * バケットはプロセスの中に持つ。本番で複数台にするなら、台数で割るか共有の保存先が要る(ADR-0043 §9 の縮退)。
 *
 * @param capacity バケットの大きさ。1 回のクエリの複雑度の上限以上にする(小さいと、上限以内のクエリが永久に通らない)
 * @param refillPerSecond 1 秒に戻す複雑度
 * @param nanoTime 単調増加の時刻(テスト用)
 */
public class ComplexityRateLimiter(
    public val capacity: Int,
    private val refillPerSecond: Int,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val buckets = ConcurrentHashMap<String, Bucket>()

    init {
        require(capacity > 0 && refillPerSecond > 0) { "バケットの大きさと戻す速さは正の値です" }
    }

    /** [cost] を消費する。足りなければ消費せず、足りるまでの時間を返す。 */
    internal fun acquire(
        client: String,
        cost: Int,
    ): Duration? = buckets.computeIfAbsent(client) { Bucket(capacity.toDouble(), nanoTime()) }.acquire(cost)

    private inner class Bucket(
        private var tokens: Double,
        private var updatedAt: Long,
    ) {
        @Synchronized
        fun acquire(cost: Int): Duration? {
            val now = nanoTime()
            tokens = minOf(capacity.toDouble(), tokens + (now - updatedAt) / NANOS_PER_SECOND * refillPerSecond)
            updatedAt = now
            if (tokens < cost) return ((cost - tokens) / refillPerSecond).seconds
            tokens -= cost
            return null
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
