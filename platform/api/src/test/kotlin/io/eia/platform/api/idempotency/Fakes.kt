package io.eia.platform.api.idempotency

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

internal val T0: Instant = Instant.parse("2026-09-30T00:00:00Z")

/** 進められる時計。 */
internal class MutableClock(
    var now: Instant = T0,
) : Clock {
    override fun now(): Instant = now

    fun advance(duration: Duration) {
        now += duration
    }
}

/**
 * 業務の更新の範囲の代わり。[write] の更新は、[run] が正常に終わったときだけ反映する(例外なら捨てる)。
 * コミットと取り消しの回数を数える。テストは 1 つずつ(入れ子にせず)使う前提。
 */
internal class FakeTransaction : TransactionBoundary {
    var commits = 0
    var rollbacks = 0
    private var pending: MutableList<() -> Unit>? = null

    override suspend fun <T> run(block: suspend () -> T): T {
        val writes = mutableListOf<() -> Unit>()
        val outer = pending
        pending = writes
        try {
            val result = block()
            writes.forEach { it() }
            commits++
            return result
        } catch (e: Throwable) {
            rollbacks++
            throw e
        } finally {
            pending = outer
        }
    }

    /** 今のトランザクションの中の更新。トランザクションの外なら、すぐに反映する。 */
    fun write(action: () -> Unit) {
        pending?.add(action) ?: action()
    }
}

/**
 * メモリ上の保存先。[IdempotencyStore] の約束どおりに振る舞い、[complete] は [transaction] に参加する。
 * [clock] は保存先の時刻(PostgreSQL の実装では DB の時刻)の代わり。
 */
internal class FakeIdempotencyStore(
    private val transaction: FakeTransaction,
    private val clock: MutableClock = MutableClock(),
) : IdempotencyStore {
    private sealed interface Row {
        val fingerprint: RequestFingerprint

        data class InProgress(
            override val fingerprint: RequestFingerprint,
            val token: String,
            val leaseExpiresAt: Instant,
        ) : Row

        data class Completed(
            override val fingerprint: RequestFingerprint,
            val response: StoredResponse,
            val expiresAt: Instant,
        ) : Row
    }

    private val mutex = Mutex()
    private val rows = mutableMapOf<IdempotencyScope, Row>()
    private var tokens = 0

    val size: Int get() = rows.size

    fun isCompleted(scope: IdempotencyScope): Boolean = rows[scope] is Row.Completed

    override suspend fun claim(
        request: IdempotencyRequest,
        lease: Duration,
    ): ClaimResult =
        mutex.withLock {
            val now = clock.now()
            val row = rows[request.scope]
            when {
                row == null || (row is Row.Completed && now >= row.expiresAt) -> acquire(request, now, lease)
                row is Row.Completed -> ClaimResult.Completed(row.fingerprint, row.response)
                row is Row.InProgress && now < row.leaseExpiresAt -> ClaimResult.InProgress(row.fingerprint, row.leaseExpiresAt - now)
                row.fingerprint == request.fingerprint -> acquire(request, now, lease)
                else -> ClaimResult.InProgress(row.fingerprint, Duration.ZERO)
            }
        }

    private fun acquire(
        request: IdempotencyRequest,
        now: Instant,
        lease: Duration,
    ): ClaimResult.Acquired {
        val token = "token-${++tokens}"
        rows[request.scope] = Row.InProgress(request.fingerprint, token, now + lease)
        return ClaimResult.Acquired(Lease(request.scope, token))
    }

    override suspend fun complete(
        lease: Lease,
        response: StoredResponse,
        retention: Duration,
    ): Boolean =
        mutex.withLock {
            val row = rows[lease.scope] as? Row.InProgress
            if (row == null || row.token != lease.token) return@withLock false
            val expiresAt = clock.now() + retention
            transaction.write { rows[lease.scope] = Row.Completed(row.fingerprint, response, expiresAt) }
            true
        }

    override suspend fun release(lease: Lease) {
        mutex.withLock {
            val row = rows[lease.scope] as? Row.InProgress
            if (row != null && row.token == lease.token) rows.remove(lease.scope)
        }
    }

    override suspend fun purgeExpired(inProgressGrace: Duration): Int =
        mutex.withLock {
            val now = clock.now()
            val expired =
                rows
                    .filterValues {
                        when (it) {
                            is Row.Completed -> now >= it.expiresAt
                            is Row.InProgress -> now >= it.leaseExpiresAt + inProgressGrace
                        }
                    }.keys
            expired.forEach(rows::remove)
            expired.size
        }
}
