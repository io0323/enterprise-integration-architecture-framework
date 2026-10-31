package io.eia.b2bgateway.application.port.outbound

import io.eia.b2bgateway.domain.receipt.InterchangeKey
import io.eia.b2bgateway.domain.receipt.PreviousReceipt
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 受け取った交換の記録(重複の判定に使う。ADR-0041 §11)。記録するのは adapters(P12 ④。原本の保管と監査の記録と同じトランザクション)。 */
public interface ReceivedInterchanges {
    /** 同じキーで前に受け取った交換。なければ null。 */
    public suspend fun find(key: InterchangeKey): Result<PreviousReceipt?, DomainError>
}
