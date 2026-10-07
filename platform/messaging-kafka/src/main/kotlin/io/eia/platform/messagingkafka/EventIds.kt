package io.eia.platform.messagingkafka

import java.security.SecureRandom
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * イベントの ID(`ce_id`)を UUIDv7(RFC 9562 §5.7)で採番する。
 *
 * - Outbox では、この ID を `id` 列(主キー)と `ce_id` 列の両方に入れる(ADR-0007 の改訂履歴 2026-10-07)。イベントの ID が 1 つになり、
 *   Outbox・WAL・Kafka・受信側をまたいで同じ値で追える。先頭 48 ビットが Unix 時刻のミリ秒なので、主キーの B-tree に末尾から入る。
 * - 同じミリ秒の中の順序は保証しない(RFC で許される)。残りの 74 ビットは [random](既定は `SecureRandom`)。
 * - Kotlin の標準ライブラリの `Uuid.generateV7()` は Kotlin 2.4.20 ではまだ実験的な API なので使わない
 *   (order の `UuidV7OrderIdGenerator` と同じ理由。安定版になったら置き換える)。
 */
public class EventIds(
    private val clock: Clock = Clock.System,
    private val random: SecureRandom = SecureRandom(),
) {
    public fun next(): Uuid {
        val millis = clock.now().toEpochMilliseconds()
        require(millis in 0..MAX_TIMESTAMP) { "UUIDv7 で表せない時刻です" }
        val mostSignificant = (millis shl TIMESTAMP_SHIFT) or VERSION_7 or (random.nextInt().toLong() and RAND_A_MASK)
        val leastSignificant = (random.nextLong() and RAND_B_MASK) or VARIANT_RFC
        return Uuid.fromLongs(mostSignificant, leastSignificant)
    }

    private companion object {
        const val MAX_TIMESTAMP = (1L shl 48) - 1
        const val TIMESTAMP_SHIFT = 16
        const val VERSION_7 = 0x7L shl 12
        const val RAND_A_MASK = 0xFFFL
        const val RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL
        const val VARIANT_RFC = Long.MIN_VALUE // 上位 2 ビットが 10
    }
}
