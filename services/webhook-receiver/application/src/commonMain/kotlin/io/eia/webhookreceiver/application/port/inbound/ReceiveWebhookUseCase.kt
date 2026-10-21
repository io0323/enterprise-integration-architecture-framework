package io.eia.webhookreceiver.application.port.inbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.webhookreceiver.domain.ReceiptOutcome
import kotlin.time.Instant

/**
 * 署名を検証した Webhook を受け取る(ADR-0035 §3)。受け取りの記録と、取り込みのトピックへの Outbox を同じトランザクションで書く。
 * 同じ [ReceiveWebhook.webhookId] の再送は、記録も Outbox も書かない。署名の検証は呼び出し元(adapters の受け口)が先に行う。
 */
public fun interface ReceiveWebhookUseCase {
    public suspend operator fun invoke(command: ReceiveWebhook): Result<ReceiptOutcome, DomainError>
}

/**
 * @property body 署名を検証した本文のバイト列(解析していない。そのまま取り込みのトピックに流す)
 * @property bodySha256 [body] の SHA-256(小文字の 16 進)
 */
public class ReceiveWebhook(
    public val webhookId: String,
    public val source: String,
    public val signedAt: Instant,
    public val contentType: String,
    public val bodySha256: String,
    public val body: ByteArray,
) {
    // 本文(個人情報を含む)はログに出さない
    override fun toString(): String = "ReceiveWebhook(webhookId=$webhookId, source=$source, signedAt=$signedAt, body=${body.size} bytes)"
}
