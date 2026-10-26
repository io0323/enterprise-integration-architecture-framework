package io.eia.iotbridge.adapters.inbound

import io.eia.iotbridge.domain.DeviceAddress
import io.eia.platform.messagingkafka.DeadLetterRecord
import io.eia.platform.messagingkafka.DeadLetterRedelivery
import io.eia.platform.messagingkafka.DeadLetterReplayer
import io.eia.platform.messagingkafka.EventMetadata
import io.eia.platform.messagingkafka.ReplayOutcome
import io.eia.shared.kernel.Result
import io.eia.shared.resilience.trace.TraceParent

/**
 * `iot.telemetry.reported.v1.dlq` の 1 件を、**デコードからやり直して**発行する(iot-bridge の `reprocess-dlq`。ADR-0039 §4)。
 * DLQ の読み取り・条件・件数の上限・dry-run は [DeadLetterReplayer] が行い、ここは 1 件の処理し直し方だけを持つ。
 *
 * - DLQ の値は MQTT から受け取ったバイト列のまま。ヘッダの元のトピック(`eiaf.mqtt.topic`)と合わせて、MQTT から受け取ったときと同じ
 *   処理([TelemetryMessageHandler.reprocess])に渡す。ce_id はメッセージの名前から作るので、受け取ったときと同じ値になる(§5。
 *   以前に届いていても、消費者の inbox で 1 回だけ処理される)。
 * - `receivedAt` は DLQ のヘッダの受け取った時刻(`eiaf.mqtt.received-at`)。ない(ヘッダを足す前に隔離した)ときは DLQ に入れた時刻
 *   (受け取った直後)。トレースは DLQ の `traceparent`(隔離したときの span)の子で続け、Correlation ID も引き継ぐ。
 * - dry-run は、今のデコーダで読めるか・トピックの形が正しいかだけを確かめる(発行できる見込みなら PLANNED、読めなければ REJECTED)。
 * - 元のトピックのヘッダがないものは処理しない(SKIPPED_SOURCE_MISMATCH。iot-bridge が隔離したものではない)。
 */
public class TelemetryDeadLetterRedelivery(
    private val handler: TelemetryMessageHandler,
    private val decoder: DevicePayloadDecoder,
) : DeadLetterRedelivery {
    @Suppress("ReturnCount") // 元の場所の記録・値の有無・dry-run のそれぞれで、処理する前に返す
    override suspend fun redeliver(
        record: DeadLetterRecord,
        execute: Boolean,
    ): ReplayOutcome {
        val topic = record.headers[TelemetryMessageHandler.MQTT_TOPIC_HEADER] ?: return ReplayOutcome.SKIPPED_SOURCE_MISMATCH
        val payload = record.value ?: return ReplayOutcome.REJECTED
        if (!execute) {
            val readable = decoder.decode(payload) is Result.Ok && DeviceAddress.fromTopic(topic) is Result.Ok
            return if (readable) ReplayOutcome.PLANNED else ReplayOutcome.REJECTED
        }
        val receivedAt =
            record.headers[TelemetryMessageHandler.MQTT_RECEIVED_AT_HEADER]?.let(DeadLetterReplayer::parseInstant) ?: record.timestamp
        val properties = PROPAGATED.mapNotNull { name -> record.headers[name]?.let { value -> name to value } }
        return handler.reprocess(ReceivedMqttMessage(topic, payload, properties, receivedAt))
    }

    private companion object {
        /** DLQ のヘッダから、MQTT のユーザープロパティとして引き継ぐもの(トレースと Correlation ID)。 */
        val PROPAGATED = listOf(TraceParent.HEADER, EventMetadata.CORRELATION_ID)
    }
}
