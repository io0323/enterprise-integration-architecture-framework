package io.eia.b2bgateway.application.port.outbound

import io.eia.b2bgateway.domain.order.CodeResolution
import io.eia.b2bgateway.domain.order.ItemCode
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 取引先ごとのコードの対応表(取引先の GLN・商品のコード → 社内の ID)。表は P12 ⑤ で作る。 */
public interface CodeMappings {
    /** 交換の中の注文に出てくるコードを、まとめて引く。対応がないコードは結果に入れない。 */
    public suspend fun resolve(
        partnerId: String,
        lookup: CodeLookup,
    ): Result<CodeResolution, DomainError>
}

/** 引くコード(買い手と届け先の GLN、商品のコード)。 */
public data class CodeLookup(
    val buyers: Set<String>,
    val deliveryParties: Set<String>,
    val items: Set<ItemCode>,
)
