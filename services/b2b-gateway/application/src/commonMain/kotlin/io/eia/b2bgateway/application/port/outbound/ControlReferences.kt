package io.eia.b2bgateway.application.port.outbound

import io.eia.b2bgateway.domain.validation.PartyIdentification
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 応答の交換の管理番号(UNB / UNZ の 0020。an..14)を、宛先の取引先ごとに振る。永続化は adapters(P12 ④)。 */
public interface ControlReferences {
    public suspend fun next(recipient: PartyIdentification): Result<String, DomainError>
}
