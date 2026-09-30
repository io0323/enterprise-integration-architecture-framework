package io.eia.order.adapters.out.persistence

import io.eia.order.application.port.outbound.OrderIdGenerator
import io.eia.order.domain.OrderId
import io.eia.shared.kernel.getOrElse
import java.security.SecureRandom
import java.util.UUID
import kotlin.time.Clock

/**
 * 注文 ID を UUIDv7(RFC 9562 §5.7)で採番する。
 *
 * - 先頭 48 ビットが Unix 時刻のミリ秒なので、ミリ秒の単位で時刻順に並ぶ。主キーの B-tree に新しい ID が末尾から入り、
 *   インデックスの局所性を保てる(UUIDv4 のように挿入位置が散らばらない)。同じミリ秒の中の順序は保証しない(RFC で許される)。
 * - 残りの 74 ビット(`rand_a` 12 ビット・`rand_b` 62 ビット)は [random](既定は `SecureRandom`)。推測されにくい。
 * - Kotlin の標準ライブラリの `kotlin.uuid.Uuid.generateV7()` は、Kotlin 2.4.20 ではまだ実験的な API(`@ExperimentalUuidApi`)の
 *   ため使わない(alpha / 実験的な API を使わない方針。libs.versions.toml)。安定版になったら置き換える。
 */
public class UuidV7OrderIdGenerator(
    private val clock: Clock = Clock.System,
    private val random: SecureRandom = SecureRandom(),
) : OrderIdGenerator {
    override fun next(): OrderId = OrderId.parse(uuid().toString()).getOrElse { error("UUID は OrderId の規則を満たします: ${it.code}") }

    internal fun uuid(): UUID {
        val millis = clock.now().toEpochMilliseconds()
        require(millis in 0..MAX_TIMESTAMP) { "UUIDv7 で表せない時刻です" }
        val mostSignificant = (millis shl TIMESTAMP_SHIFT) or VERSION_7 or (random.nextInt().toLong() and RAND_A_MASK)
        val leastSignificant = (random.nextLong() and RAND_B_MASK) or VARIANT_RFC
        return UUID(mostSignificant, leastSignificant)
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
