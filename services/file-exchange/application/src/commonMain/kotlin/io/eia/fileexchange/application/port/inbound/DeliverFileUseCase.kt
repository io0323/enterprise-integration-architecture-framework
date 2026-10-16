package io.eia.fileexchange.application.port.inbound

import io.eia.fileexchange.domain.DeliveryOutcome
import io.eia.fileexchange.domain.PartnerId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 受け取った [name] のファイルを、データセットを送る取引先ごとに配送する(ADR-0032 §8)。受け取りの実行の後のステップ(ロックの中)で呼ぶ。
 * quarantine に移したファイルは送らない。送信済みの取引先には送らない。送る前に相手の側に正式名があれば、送らずに送信済みにする。
 * 1 つの取引先の失敗で、ほかの取引先への配送を止めない(失敗は [DeliveryReport.failures] に入れる)。
 */
public interface DeliverFileUseCase {
    public suspend operator fun invoke(name: String): Result<DeliveryReport, DomainError>
}

/**
 * @property quarantined quarantine に移したファイルなので、送らなかった
 * @property delivered 取引先ごとの結果(成功したものだけ)
 * @property failures 送れなかった取引先と、その失敗
 */
public data class DeliveryReport(
    val name: String,
    val quarantined: Boolean,
    val delivered: List<PartnerDelivery>,
    val failures: List<PartnerFailure>,
)

public data class PartnerDelivery(
    val partner: PartnerId,
    val outcome: DeliveryOutcome,
)

public data class PartnerFailure(
    val partner: PartnerId,
    val error: DomainError,
)
