package io.eia.platform.api.idempotency

import io.eia.shared.kernel.IdempotencyKey
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * `Idempotency-Key` の記録の保存先(Port。ADR-0022 §3)。PostgreSQL の実装は各サービスの adapters が持つ(order は P05 ④a)。
 *
 * 記録は (クライアント, キー) ごとに 1 件で、状態は「処理中」(リース付き)か「完了」(保存した応答付き)。
 * 実装は次の約束を守る。[IdempotencyHandler] はこの約束の上で判定する。
 *
 * - [claim] は 1 つの原子的な操作にする(同じ範囲を同時に受け付けて、2 つが処理中にならない)。
 * - [complete] は、呼び出し側(業務の更新)のトランザクションに参加する。業務の更新と応答の保存は、一緒に確定するか、一緒に取り消される。
 * - [complete] と [release] は、リースのトークンが一致するときだけ記録を変える(フェンシング)。
 *   リースが切れて別の要求に引き継がれた後に、前の所有者が遅れて戻っても、記録を上書きできない。
 */
public interface IdempotencyStore {
    /**
     * [request] を受け付ける。
     *
     * | 既存の記録 | 結果 |
     * |---|---|
     * | ない、または完了の保持期限([StoredResponse] の `expiresAt`)を過ぎた | 処理中(リースの期限 `now + lease`)として保存し [ClaimResult.Acquired] |
     * | 完了で保持期限内 | [ClaimResult.Completed](保存した応答) |
     * | 処理中でリースの期限内 | [ClaimResult.InProgress] |
     * | 処理中でリースの期限切れ、指紋が同じ | 新しいトークンとリースで引き継ぎ [ClaimResult.Acquired] |
     * | 処理中でリースの期限切れ、指紋が違う | [ClaimResult.InProgress](引き継がない。指紋の違いは呼び出し側が判定する) |
     *
     * [ClaimResult.Completed] と [ClaimResult.InProgress] は、既存の記録の指紋を返す。
     */
    public suspend fun claim(
        request: IdempotencyRequest,
        now: Instant,
        lease: Duration,
    ): ClaimResult

    /**
     * [lease] の処理を完了にし、[response] を [expiresAt] まで保存する。呼び出し側のトランザクションに参加する。
     *
     * @return トークンが一致して保存したら `true`。引き継がれた後など、一致しなければ何も変えずに `false`
     */
    public suspend fun complete(
        lease: Lease,
        response: StoredResponse,
        expiresAt: Instant,
    ): Boolean

    /** [lease] の処理中の記録を消す(応答を保存しないとき・例外のとき)。トークンが一致しなければ何もしない。 */
    public suspend fun release(lease: Lease)

    /** 保持期限を過ぎた完了の記録を消し、消した件数を返す。定期的に呼ぶ(サービスのジョブで結線する)。 */
    public suspend fun purgeExpired(now: Instant): Int
}

/** 冪等の範囲。キーはクライアントごとに独立する(別のクライアントが同じキーを使っても影響しない)。 */
public data class IdempotencyScope(
    /** 呼び出し元のクライアント(JWT の `azp` など)。 */
    public val clientId: String,
    public val key: IdempotencyKey,
)

/** 受け付ける要求。[fingerprint] で、同じキーの再送が同じ要求かを判定する。 */
public data class IdempotencyRequest(
    public val scope: IdempotencyScope,
    public val fingerprint: RequestFingerprint,
)

/** 処理中の記録の所有権。[token] は受け付けるたび(引き継ぎを含む)に新しくする。 */
public data class Lease(
    public val scope: IdempotencyScope,
    public val token: String,
    public val expiresAt: Instant,
)

/** [IdempotencyStore.claim] の結果。 */
public sealed interface ClaimResult {
    /** 処理してよい。 */
    public data class Acquired(
        public val lease: Lease,
    ) : ClaimResult

    /** 完了している。 */
    public data class Completed(
        public val fingerprint: RequestFingerprint,
        public val response: StoredResponse,
    ) : ClaimResult

    /** 処理中。[leaseExpiresAt] はリースの期限(`Retry-After` の目安)。 */
    public data class InProgress(
        public val fingerprint: RequestFingerprint,
        public val leaseExpiresAt: Instant,
    ) : ClaimResult
}
