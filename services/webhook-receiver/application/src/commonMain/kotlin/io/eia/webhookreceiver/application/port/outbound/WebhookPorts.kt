package io.eia.webhookreceiver.application.port.outbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.webhookreceiver.domain.WebhookReceipt
import kotlin.time.Duration

/** 業務の更新の範囲(トランザクション)。[block] が `Ok` を返せば確定し、`Err` を返すか例外を投げれば取り消す。 */
public interface TransactionRunner {
    public suspend fun <T> inTransaction(block: suspend () -> Result<T, DomainError>): Result<T, DomainError>
}

/** 受け取りの記録(`webhook_receipt`。主キーは `webhook_id`)。 */
public interface WebhookReceipts {
    /**
     * まだ記録がなければ [receipt] を記録して null を返す。記録済みなら、記録済みのものを返す(書かない)。
     * 同じ ID の同時の受け取りでは、片方だけが記録する(もう片方は確定を待って、記録済みのものを返す)。トランザクションの中で呼ぶ。
     */
    public suspend fun insertIfAbsent(receipt: WebhookReceipt): Result<WebhookReceipt?, DomainError>

    /** 受け取ってから [retention] を過ぎた記録を、[limit] 件まで消す(DB の時計で判定する)。消した件数を返す。 */
    public suspend fun purgeOlderThan(
        retention: Duration,
        limit: Int,
    ): Result<Int, DomainError>
}

/**
 * 社内の取り込みのトピック `integration.crm-webhook.received.v1` への Outbox(INT-INTEGRATION-001。ADR-0007)。
 * 受け取りの記録と**同じトランザクションの中**で呼ぶ。書けなければ、受け取りの記録も取り消す(送り手には 503 を返し、再送させる)。
 */
public interface IngestOutbox {
    public suspend fun received(
        receipt: WebhookReceipt,
        contentType: String,
        body: ByteArray,
    ): Result<Unit, DomainError>
}
