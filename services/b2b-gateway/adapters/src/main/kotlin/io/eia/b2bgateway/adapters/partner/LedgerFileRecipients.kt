package io.eia.b2bgateway.adapters.partner

import io.eia.b2bgateway.application.port.outbound.FileRecipients
import io.eia.platform.tradingpartner.Direction
import io.eia.platform.tradingpartner.TradingPartnerLedger

/**
 * 取引先の台帳で、データセットのファイルを受け取る取引先(`documents` に、向き outbound・連携 INT-SALES-004・そのデータセットがある取引先。
 * ADR-0040 §2・§7)。台帳は起動時に読む。
 */
public class LedgerFileRecipients(
    private val ledger: TradingPartnerLedger,
) : FileRecipients {
    override fun of(dataset: String): List<String> =
        ledger.partners
            .filter { partner ->
                partner.documents.any { it.direction == Direction.OUTBOUND && it.integration == SALES_FILE && it.dataset == dataset }
            }.map { it.id }

    private companion object {
        // 日次の売上ファイル(連携カタログ)
        const val SALES_FILE = "INT-SALES-004"
    }
}
