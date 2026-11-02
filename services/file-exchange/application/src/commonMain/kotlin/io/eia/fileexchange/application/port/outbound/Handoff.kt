package io.eia.fileexchange.application.port.outbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlin.time.Instant

/**
 * 取引先に送るファイルの受け渡しの場所(S3 互換ストレージの `outbound/sales-daily/`。ADR-0032 §8・ADR-0040 §7)。
 * 取引先への送信は b2b-gateway が行う。file-exchange は合格のファイルをここに写すだけ。
 */
public interface Handoff {
    /** archive の [name] のデータファイル [dataFile] を写し、マニフェストを最後に写す(何度写しても同じ結果)。 */
    public suspend fun handOff(
        name: String,
        dataFile: String,
    ): Result<Unit, DomainError>
}

/**
 * 切り替えより前の、取引先への配送の記録(file-exchange の DB の `delivery`。P12 ⑦b から読み取り専用。ADR-0032 §8)。
 * 1 回きりの移行で、まだ送り終えていないファイルを探すのに使う。
 */
public interface PastDeliveries {
    /**
     * [since] 以降に archive に収めたファイルのうち、配送の記録がない(配送を始める前だった)か、送り終えていない取引先があるもの
     * (名前の順)。
     */
    public suspend fun unsent(since: Instant): Result<List<String>, DomainError>
}
