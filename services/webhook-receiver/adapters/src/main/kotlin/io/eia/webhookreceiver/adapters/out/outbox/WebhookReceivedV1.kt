package io.eia.webhookreceiver.adapters.out.outbox

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * 取り込みのイベントの型(contracts/avro/integration/WebhookReceived.avsc。INT-INTEGRATION-001)。
 * `@SerialName` は契約の record のフルネーム(ADR-0025 §1)。[body] は署名を検証した本文のバイト列(解析していない)。
 */
@Serializable
@SerialName("io.eia.events.integration.WebhookReceived")
@Suppress("LongParameterList") // 契約の record の項目(WebhookReceived.avsc)と 1 対 1
public class WebhookReceivedV1(
    public val webhookId: String,
    public val source: String,
    public val signedAt: Instant,
    public val receivedAt: Instant,
    public val contentType: String,
    public val bodySha256: String,
    public val body: ByteArray,
) {
    // 本文(個人情報を含む)はログに出さない
    override fun toString(): String = "WebhookReceivedV1(webhookId=$webhookId, source=$source, body=${body.size} bytes)"
}
