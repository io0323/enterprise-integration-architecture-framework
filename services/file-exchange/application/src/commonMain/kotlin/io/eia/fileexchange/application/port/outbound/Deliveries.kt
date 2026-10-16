package io.eia.fileexchange.application.port.outbound

import io.eia.fileexchange.domain.Delivery
import io.eia.fileexchange.domain.PartnerId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 取引先への配送の記録(file-exchange の DB の `delivery`。ファイルの名前と取引先の組ごとに 1 行。ADR-0032 §8)。 */
public interface Deliveries {
    public suspend fun find(
        name: String,
        partner: PartnerId,
    ): Result<Delivery?, DomainError>

    /** 配送を「送信中」として記録する。同じ組の記録がすでにあれば、書かずにそれを返す。記録された行を返す。 */
    public suspend fun begin(
        name: String,
        partner: PartnerId,
        remoteName: String,
    ): Result<Delivery, DomainError>

    /** 送り終えた(SENDING → SENT)。[alreadyPresent] は、送らずに相手の側にあったものを送信済みにしたとき。 */
    public suspend fun sent(
        name: String,
        partner: PartnerId,
        alreadyPresent: Boolean,
    ): Result<Unit, DomainError>
}

/** 取引先の台帳(取引先の ID ごとの送り先・鍵。ADR-0032 §8)。 */
public interface Partners {
    /** データセット [dataset](`{system}_{dataset}`)を送る取引先。ID の順。 */
    public fun recipients(dataset: String): List<PartnerId>
}

/**
 * 取引先の送り先(SFTP)。送る前に暗号化・署名する(PGP)。依存先ごとの Timeout・Retry・Circuit Breaker は実装が付ける(ADR-0021)。
 * どの操作も、同じ引数で呼び直して害がない(送り直しは同じ正式名で上書き)。
 */
public interface PartnerOutbox {
    /** 取引先の側に、正式名 [remoteName] のファイルがあるか(一時名のものは数えない)。 */
    public suspend fun exists(
        partner: PartnerId,
        remoteName: String,
    ): Result<Boolean, DomainError>

    /** archive にある [name] のデータファイル [dataFile] を、暗号化・署名して、取引先に正式名 [remoteName] で送る。 */
    public suspend fun send(
        partner: PartnerId,
        name: String,
        dataFile: String,
        remoteName: String,
    ): Result<Unit, DomainError>
}
