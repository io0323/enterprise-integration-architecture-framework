package io.eia.platform.messagingkafka

import kotlin.time.Instant

/**
 * DLQ の 1 件の処理し直し方([DeadLetterReplayer.replay] に渡す。ADR-0028 §6)。
 *
 * 渡さなければ、[DeadLetterReplayer] はキー・値・ヘッダをそのまま元のトピックに戻す。値を読み直してから出し直す必要がある DLQ
 * (例 iot-bridge の DLQ。値は MQTT から受け取ったバイト列のままで、デコーダを直した後にデコードからやり直す。ADR-0039 §4)は、
 * サービスがこれを実装して渡す。DLQ の読み取り・条件・件数の上限・dry-run は [DeadLetterReplayer] が共通に行う(同じ仕組みを 2 つ持たない)。
 */
public fun interface DeadLetterRedelivery {
    /**
     * [record] を処理し直す。
     *
     * @param execute false なら dry-run。送らずに見込み([ReplayOutcome.PLANNED]、受け付けない見込みなら [ReplayOutcome.REJECTED])を返す
     */
    public suspend fun redeliver(
        record: DeadLetterRecord,
        execute: Boolean,
    ): ReplayOutcome
}

/**
 * DLQ のレコード(Kafka のクライアントの型を使わない形)。
 *
 * @property headers ヘッダ(同じ名前が複数あれば最後のもの。値は UTF-8 の文字列)
 * @property timestamp Kafka のレコードの時刻(DLQ に入れた時刻)
 */
public class DeadLetterRecord(
    public val entry: ReplayEntry,
    public val key: ByteArray?,
    public val value: ByteArray?,
    public val headers: Map<String, String>,
    public val timestamp: Instant,
)
