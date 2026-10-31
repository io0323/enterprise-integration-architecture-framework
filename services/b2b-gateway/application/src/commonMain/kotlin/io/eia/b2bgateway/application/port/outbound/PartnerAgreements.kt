package io.eia.b2bgateway.application.port.outbound

import io.eia.b2bgateway.domain.agreement.PartnerAgreement
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 取引先との合意(TPA)。実装は取引先の台帳(P12 ③)。 */
public interface PartnerAgreements {
    /** 取引先の ID の合意。台帳になければ null。 */
    public suspend fun find(partnerId: String): Result<PartnerAgreement?, DomainError>
}
