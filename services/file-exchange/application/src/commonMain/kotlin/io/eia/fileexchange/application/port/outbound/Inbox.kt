package io.eia.fileexchange.application.port.outbound

import io.eia.fileexchange.domain.ExtraArrival
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlin.time.Instant

/**
 * 受け口(S3 互換ストレージの `inbox/`)のファイル。検査は判定だけを返し、移動は別に呼ぶ(ADR-0032 §3・§4)。
 * 移動はどれも、途中で落ちても同じ引数で呼び直せば同じ結果で終わる。
 */
public interface Inbox {
    /** [name] のファイルを検査する(マニフェスト・SHA-256・件数・スキーマの版など)。マニフェストがなければ失敗。 */
    public suspend fun inspect(name: String): Result<Inspection, DomainError>

    /** 合格のファイルを archive/ に移す。 */
    public suspend fun archive(
        name: String,
        dataFile: String,
    ): Result<Unit, DomainError>

    /** 不合格のファイルを quarantine/ に移し、理由のファイルを置く。 */
    public suspend fun quarantine(
        name: String,
        reason: String,
        detail: String,
    ): Result<Unit, DomainError>

    /** 受け取り済みの名前で届いたファイルを、[arrival] ごとに分けた quarantine/ の場所に移す(前に届いたものを上書きしない)。 */
    public suspend fun quarantineExtra(arrival: ExtraArrival): Result<Unit, DomainError>

    /** 受け口にマニフェストが残っているファイル。 */
    public suspend fun waiting(): Result<List<WaitingFile>, DomainError>

    /** [name] のマニフェストが受け口にあれば、その情報(今読み直す)。 */
    public suspend fun waiting(name: String): Result<WaitingFile?, DomainError>
}

/** 検査の判定。 */
public sealed interface Inspection {
    public data class Accepted(
        val sha256: String,
        val dataFile: String,
    ) : Inspection

    /**
     * @property reason 理由のコード(例 `checksum_mismatch`)
     * @property sha256 マニフェストが読めたときだけ
     */
    public data class Rejected(
        val reason: String,
        val detail: String,
        val sha256: String?,
    ) : Inspection
}

/**
 * 受け口に残っているファイル。
 *
 * @property sha256 マニフェストの SHA-256(読めなければ null)
 * @property createdAt マニフェストの createdAt(読めなければ null。滞留の時間の目安)
 */
public data class WaitingFile(
    val name: String,
    val sha256: String?,
    val createdAt: Instant?,
)
