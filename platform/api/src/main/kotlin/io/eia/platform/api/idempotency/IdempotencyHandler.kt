package io.eia.platform.api.idempotency

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * 業務の更新の範囲(トランザクション)。[run] の [block] が例外で終われば、その中の更新をすべて取り消して例外を伝える。
 * 呼び出し側(サービスの adapters)が、業務の更新と [IdempotencyStore.complete] が同じトランザクションになるように実装する。
 */
public interface TransactionBoundary {
    public suspend fun <T> run(block: suspend () -> T): T
}

/** [IdempotencyHandler.execute] の結果。HTTP への写し方は [respondIdempotently]。 */
public sealed interface IdempotencyOutcome {
    /** 今回処理した。応答は [response](保存したかどうかによらず、処理が返したもの)。 */
    public class Processed(
        public val response: HttpSnapshot,
    ) : IdempotencyOutcome

    /** 同じ要求の再送。保存した応答を返す(`Idempotent-Replayed: true`)。 */
    public class Replayed(
        public val response: StoredResponse,
    ) : IdempotencyOutcome

    /** 同じキーで内容の違う要求(422 `idempotency-key-reused`)。 */
    public data object KeyReused : IdempotencyOutcome

    /** 同じキーの要求を処理中(409 `idempotency-request-in-progress`)。[retryAfter] はリースの残り時間(分からなければ `null`)。 */
    public data class InProgress(
        public val retryAfter: Duration?,
    ) : IdempotencyOutcome
}

/**
 * `Idempotency-Key` の判定と処理(Framework 5.4。ADR-0022 §3)。HTTP の枠組みに依存しない中核で、Ktor の結線は [respondIdempotently]。
 *
 * 1. [IdempotencyStore.claim] で受け付ける。完了済みなら、指紋が同じなら保存した応答を、違えば [IdempotencyOutcome.KeyReused] を返す。
 *    処理中なら、指紋が同じなら [IdempotencyOutcome.InProgress]、違えば [IdempotencyOutcome.KeyReused]。
 * 2. 受け付けたら、[TransactionBoundary] の中で処理し、応答を保存する([IdempotencyStore.complete])。業務の更新と応答の保存は一緒に確定する。
 * 3. **保存しない応答**(5xx・429・408。[IdempotencyConfig.isStorable])は、トランザクションを取り消してから返し、処理中の記録を消す。
 *    「保存しない = 副作用なし」にして、クライアントが同じキーで再試行しても二重の処理にならないようにする。
 *    この前提は、副作用がすべて同じトランザクションの中にある場合に限る(ADR-0022 §3)。
 * 4. 例外・キャンセルでは、トランザクションを取り消し、処理中の記録を消して伝える。
 * 5. 保存のときにリースを失っていた(期限が切れて引き継がれた)ら、トランザクションを取り消し、改めて記録を見て返す
 *    (引き継いだ側が完了していれば、その応答を返す)。
 * 6. **指紋が違えば、状態(処理中・完了)やリースの有効・期限切れに関係なく 422**([IdempotencyOutcome.KeyReused])。
 *
 * リースと保持期限の時刻は保存先が決める([IdempotencyStore])。このクラスはアプリの時計を使わない。
 */
public class IdempotencyHandler(
    private val store: IdempotencyStore,
    private val config: IdempotencyConfig = IdempotencyConfig(),
) {
    public suspend fun execute(
        request: IdempotencyRequest,
        transaction: TransactionBoundary,
        process: suspend () -> HttpSnapshot,
    ): IdempotencyOutcome =
        when (val claim = store.claim(request, config.lease)) {
            is ClaimResult.Acquired -> processWith(claim.lease, request, transaction, process)
            else -> existing(claim, request)
        }

    private fun existing(
        claim: ClaimResult,
        request: IdempotencyRequest,
    ): IdempotencyOutcome =
        when (claim) {
            is ClaimResult.Completed -> {
                if (claim.fingerprint == request.fingerprint) IdempotencyOutcome.Replayed(claim.response) else IdempotencyOutcome.KeyReused
            }

            is ClaimResult.InProgress -> {
                if (claim.fingerprint == request.fingerprint) {
                    IdempotencyOutcome.InProgress(claim.leaseRemaining.coerceAtLeast(Duration.ZERO))
                } else {
                    IdempotencyOutcome.KeyReused
                }
            }

            // 取り直した記録を処理しない(リースを失った後の確認でだけ起こる)。いったん処理中として返す
            is ClaimResult.Acquired -> {
                IdempotencyOutcome.InProgress(retryAfter = null)
            }
        }

    @Suppress("TooGenericExceptionCaught") // 例外は記録を消してから、そのまま伝える
    private suspend fun processWith(
        lease: Lease,
        request: IdempotencyRequest,
        transaction: TransactionBoundary,
        process: suspend () -> HttpSnapshot,
    ): IdempotencyOutcome =
        try {
            IdempotencyOutcome.Processed(transaction.run { completeWith(lease, process()) })
        } catch (e: NotStored) {
            release(lease)
            IdempotencyOutcome.Processed(e.response)
        } catch (_: LeaseLost) {
            logger.warn("処理中にリースを失ったため、処理を取り消しました(リースの期限より処理が長い)")
            afterLeaseLost(request)
        } catch (e: Throwable) {
            release(lease)
            throw e
        }

    /**
     * トランザクションの中で、[response] を保存する。保存しない応答([NotStored])とリースを失ったとき([LeaseLost])は、
     * 例外でトランザクションを取り消す(業務の更新を残さない)。
     */
    private suspend fun completeWith(
        lease: Lease,
        response: HttpSnapshot,
    ): HttpSnapshot {
        if (!IdempotencyConfig.isStorable(response.status)) throw NotStored(response)
        if (!store.complete(lease, store(response), config.retention)) throw LeaseLost()
        return response
    }

    /** リースを失った後に、記録を見て返す。引き継いだ側が完了していれば、その応答(または指紋の違い)を返す。 */
    private suspend fun afterLeaseLost(request: IdempotencyRequest): IdempotencyOutcome =
        when (val claim = store.claim(request, config.lease)) {
            is ClaimResult.Acquired -> {
                release(claim.lease)
                IdempotencyOutcome.InProgress(retryAfter = null)
            }

            else -> {
                existing(claim, request)
            }
        }

    private fun store(response: HttpSnapshot): StoredResponse {
        check(response.body.size <= config.maxStoredBodyBytes) {
            "冪等の応答の本文が上限 ${config.maxStoredBodyBytes} バイトを超えています(${response.body.size} バイト)"
        }
        val headers = response.headers.filter { (name, _) -> config.storedHeaders.any { it.equals(name, ignoreCase = true) } }
        return StoredResponse(response.status, headers, response.body)
    }

    /** キャンセルされた後でも消す(残すと、リースの期限まで同じキーの再送が 409 になる)。 */
    private suspend fun release(lease: Lease) = withContext(NonCancellable) { store.release(lease) }

    /** 保存しない応答で、トランザクションを取り消すための例外。 */
    private class NotStored(
        val response: HttpSnapshot,
    ) : RuntimeException(null, null, false, false)

    /** 保存のときにリースを失っていた。トランザクションを取り消すための例外。 */
    private class LeaseLost : RuntimeException(null, null, false, false)

    private companion object {
        private val logger = LoggerFactory.getLogger(IdempotencyHandler::class.java)
    }
}
