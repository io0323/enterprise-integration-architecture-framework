package io.eia.legacyorderacl.adapters.inbound

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 生の CDC のトピック `_cdc.legacy.public.t_juchu` の値(Debezium の Envelope。ADR-0026 §3)。
 *
 * avro4k は型とスキーマを record のフルネームで対応させるため、`@SerialName` は Debezium が作る名前にする
 * (`{topic.prefix}.{schema}.{table}.Envelope` / `.Value`、`io.debezium.connector.postgresql.Source`)。
 * 使う項目だけを持つ。書き手のスキーマのほかの項目(`transaction`・`ts_*` など)は読み飛ばす。
 * Converter はスキーマを参照なしの 1 つのスキーマで登録する(`apicurio.registry.dereference-schema=true`)。
 */
@Serializable
@SerialName("_cdc.legacy.public.t_juchu.Envelope")
internal data class RawJuchuEnvelope(
    val before: RawJuchuRow? = null,
    val after: RawJuchuRow? = null,
    val source: RawDebeziumSource,
    /** c(登録)/ u(更新)/ d(削除)/ r(Snapshot で読んだ) */
    val op: String,
)

/** t_juchu の 1 行(レガシーの形式のまま。NUMERIC は decimal.handling.mode=string で文字列、時刻は MicroTimestamp)。 */
@Serializable
@SerialName("_cdc.legacy.public.t_juchu.Value")
internal data class RawJuchuRow(
    @SerialName("col_01") val sequence: String,
    @SerialName("col_02") val orderNumber: String,
    @SerialName("col_03") val statusCode: String,
    @SerialName("col_04") val customerName: String,
    @SerialName("col_05") val customerCode: String,
    @SerialName("col_06") val amount: String,
    @SerialName("col_07") val orderedAtLocalMicros: Long,
    @SerialName("col_08") val updatedAtLocalMicros: Long,
)

/** Debezium の source(変更の位置)。 */
@Serializable
@SerialName("io.debezium.connector.postgresql.Source")
internal data class RawDebeziumSource(
    /** true / first / first_in_data_collection / last_in_data_collection / last / incremental / false */
    val snapshot: String? = "false",
    val lsn: Long? = null,
    /** コミットの時刻(UTC のエポックからのミリ秒)。Snapshot では読み取った時刻 */
    @SerialName("ts_ms") val committedAtMillis: Long,
    @SerialName("ts_us") val committedAtMicros: Long? = null,
)

/** 生の CDC のトピックのキー(message.key.columns の受注番号)。 */
@Serializable
@SerialName("_cdc.legacy.public.t_juchu.Key")
internal data class RawJuchuKey(
    @SerialName("col_02") val orderNumber: String,
)
