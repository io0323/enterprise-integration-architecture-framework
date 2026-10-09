package io.eia.payment.adapters.out.outbox

import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaSubject

/**
 * payment-service が書く返事のイベントのトピックと契約のスキーマ(INT-PAYMENT-002。ADR-0025 §2・§3)。
 * スキーマは `contracts/avro/payment` のファイルを、ビルド時にリソースへコピーしたもの(adapters の build.gradle.kts)。
 */
public object PaymentEventSchemas {
    public val PAYMENT_AUTHORIZED: EventTopic = EventTopic.of("payment.payment.authorized.v1")
    public val PAYMENT_DECLINED: EventTopic = EventTopic.of("payment.payment.declined.v1")
    public val PAYMENT_VOIDED: EventTopic = EventTopic.of("payment.payment.voided.v1")

    public val paymentAuthorized: SchemaSubject by lazy { subject(PAYMENT_AUTHORIZED, "PaymentAuthorized") }
    public val paymentDeclined: SchemaSubject by lazy { subject(PAYMENT_DECLINED, "PaymentDeclined") }
    public val paymentVoided: SchemaSubject by lazy { subject(PAYMENT_VOIDED, "PaymentVoided") }

    /** 書くすべてのスキーマ(起動時に ID を解決する対象)。 */
    public val subjects: List<SchemaSubject> get() = listOf(paymentAuthorized, paymentDeclined, paymentVoided)

    /** 返事の Serializer(起動時に解決した ID を使う)。 */
    public class Serializers(
        ids: SchemaIdBook,
    ) {
        public val authorized: AvroEventSerializer<PaymentAuthorizedV1> =
            AvroEventSerializer(PAYMENT_AUTHORIZED, paymentAuthorized, PaymentAuthorizedV1.serializer(), ids)
        public val declined: AvroEventSerializer<PaymentDeclinedV1> =
            AvroEventSerializer(PAYMENT_DECLINED, paymentDeclined, PaymentDeclinedV1.serializer(), ids)
        public val voided: AvroEventSerializer<PaymentVoidedV1> =
            AvroEventSerializer(PAYMENT_VOIDED, paymentVoided, PaymentVoidedV1.serializer(), ids)
    }

    private fun subject(
        topic: EventTopic,
        record: String,
    ): SchemaSubject {
        val path = "/contracts/avro/payment/$record.avsc"
        val schema =
            requireNotNull(PaymentEventSchemas::class.java.getResource(path)) { "$path がリソースにありません(contracts からのコピー)" }.readText()
        return SchemaSubject(topic.name, schema)
    }
}
