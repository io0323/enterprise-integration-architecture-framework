package io.eia.b2bgateway.application.port.outbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 取引先から前に受け付けた注文(業務の段階の注文番号の重複。ORD007)。記録は adapters(P12 ④・⑤)。 */
public interface ReceivedOrders {
    /** 同じ取引先から、同じ注文番号を前に受け付けていれば true。 */
    public suspend fun exists(
        partnerId: String,
        orderNumber: String,
    ): Result<Boolean, DomainError>
}
