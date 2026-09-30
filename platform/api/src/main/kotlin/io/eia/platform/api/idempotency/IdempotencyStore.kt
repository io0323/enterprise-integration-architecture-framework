package io.eia.platform.api.idempotency

import io.eia.shared.kernel.IdempotencyKey
import kotlin.time.Duration

/**
 * `Idempotency-Key` の記録の保存先(Port。ADR-0022 §3)。PostgreSQL の実装は各サービスの adapters が持つ(order は P05 ④a-2)。
 *
 * 記録は (クライアント, キー) ごとに 1 件で、状態は「処理中」(リース付き)か「完了」(保存した応答付き)。
 * 実装は次の約束を守る。[IdempotencyHandler] はこの約束の上で判定する。
 *
 * - [claim] は 1 つの原子的な操作にする(同じ範囲を同時に受け付けて、2 つが処理中にならない)。
 * - [complete] は、呼び出し側(業務の更新)のトランザクションに参加する。業務の更新と応答の保存は、一緒に確定するか、一緒に取り消される。
 * - [complete] と [release] は、リースのトークンが一致するときだけ記録を変える(フェンシング)。
 *   リースが切れて別の要求に引き継がれた後に、前の所有者が遅れて戻っても、記録を上書きできない。
 * - **リースと保持期限の設定と判定は、保存先の時刻で行う**(PostgreSQL なら DB の時刻)。呼び出し側(アプリ)の時計は使わない。
 *   複数のインスタンスの時計のずれで、有効なリースを横取りしたり、期限の前の記録を消したりしないため。そのため、この Port は時刻を
 *   引数に取らず、期間([Duration])だけを受け取る。
 */
public interface IdempotencyStore {
    /**
     * [request] を受け付ける。処理中にするときは、リースの期限を「保存先の現在時刻 + [lease]」にする。
     *
     * | 既存の記録 | 結果 |
     * |---|---|
     * | ない、または完了の保持期限を過ぎた | 処理中として保存し [ClaimResult.Acquired] |
     * | 完了で保持期限内 | [ClaimResult.Completed](保存した応答) |
     * | 処理中でリースの期限内 | [ClaimResult.InProgress] |
     * | 処理中でリースの期限切れ、指紋が同じ | 新しいトークンとリースで引き継ぎ [ClaimResult.Acquired] |
     * | 処理中でリースの期限切れ、指紋が違う | [ClaimResult.InProgress](引き継がない) |
     *
     * [ClaimResult.Completed] と [ClaimResult.InProgress] は既存の記録の指紋を返す。指紋が違えば、状態やリースの有効・期限切れに
     * 関係なく、呼び出し側が 422(`idempotency-key-reused`)にする([IdempotencyHandler])。
     */
    public suspend fun claim(
        request: IdempotencyRequest,
        lease: Duration,
    ): ClaimResult

    /**
     * [lease] の処理を完了にし、[response] を「保存先の現在時刻 + [retention]」まで保存する。呼び出し側のトランザクションに参加する。
     *
     * @return トークンが一致して保存したら `true`。引き継がれた後など、一致しなければ何も変えずに `false`
     */
    public suspend fun complete(
        lease: Lease,
        response: StoredResponse,
        retention: Duration,
    ): Boolean

    /** [lease] の処理中の記録を消す(応答を保存しないとき・例外のとき)。トークンが一致しなければ何もしない。 */
    public suspend fun release(lease: Lease)

    /**
     * 期限を過ぎた記録を消し、消した件数を返す。定期的に呼ぶ(サービスのジョブで結線する)。
     *
     * - 完了の記録: 保持期限を過ぎたもの。
     * - 処理中の記録: リースの期限に [inProgressGrace] を足した時刻を過ぎたもの(処理中のままプロセスが落ちて、再送が来なかった記録)。
     *   猶予を足すのは、リースが切れた直後に同じキーの再送が引き継ごうとしている記録を消さないため。猶予にはリースの長さを渡す。
     */
    public suspend fun purgeExpired(inProgressGrace: Duration): Int
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

/** 処理中の記録の所有権。[token] は受け付けるたび(引き継ぎを含む)に新しくする。期限は保存先が持つ。 */
public data class Lease(
    public val scope: IdempotencyScope,
    public val token: String,
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

    /** 処理中。[leaseRemaining] は保存先の時刻で測ったリースの残り時間(期限切れなら 0。`Retry-After` の目安)。 */
    public data class InProgress(
        public val fingerprint: RequestFingerprint,
        public val leaseRemaining: Duration,
    ) : ClaimResult
}
