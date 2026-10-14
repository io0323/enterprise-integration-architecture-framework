package io.eia.batchetl.application.port.outbound

import io.eia.batchetl.domain.BusinessDay
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 分析の日次のパーティション(mart)。 */
public interface OrderMart {
    /** 作り直しの印が付いている日。 */
    public suspend fun dirtyDays(): Result<List<BusinessDay>, DomainError>

    /** [day] のパーティションを raw から作り直し(置換)、印を消す。1 つのトランザクション。その日の注文の件数を返す。 */
    public suspend fun rebuild(day: BusinessDay): Result<Int, DomainError>
}
