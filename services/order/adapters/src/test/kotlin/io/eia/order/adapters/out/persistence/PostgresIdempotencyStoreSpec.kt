package io.eia.order.adapters.out.persistence

import io.eia.platform.api.idempotency.IdempotencyRequest
import io.eia.platform.api.idempotency.IdempotencyScope
import io.eia.platform.api.idempotency.RequestFingerprint
import io.eia.shared.kernel.IdempotencyKey
import io.eia.shared.kernel.Result
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.Connection
import kotlin.time.Duration.Companion.minutes

private val SCOPE = IdempotencyScope("client-a", (IdempotencyKey.parse("key-1") as Result.Ok).value)
private val FINGERPRINT = RequestFingerprint.of("POST", "/v1/orders", "application/json", """{"sku":"A"}""".toByteArray())
private val REQUEST = IdempotencyRequest(SCOPE, FINGERPRINT)

/** どのトランザクションの使い方でも、[FakeJdbc] の接続で実行する。 */
private fun FakeJdbc.store(): PostgresIdempotencyStore {
    val connection = connection
    return PostgresIdempotencyStore(
        object : IdempotencyConnections {
            override suspend fun <T> newTransaction(block: (Connection) -> T): T = block(connection)

            override suspend fun <T> joinOrNewTransaction(block: (Connection) -> T): T = block(connection)

            override suspend fun <T> currentTransaction(block: (Connection) -> T): T = block(connection)
        },
    )
}

/**
 * 統合テスト(PostgresIdempotencyStoreIT)で再現できない分岐だけを、JDBC の代わり(FakeJdbc)で確かめる。
 * SQL のパラメータと結果の写し方は、実際の PostgreSQL での統合テストで確かめる(Issue #56)。
 */
class PostgresIdempotencyStoreSpec :
    FunSpec({
        test("引き継げない記録を読む前に、期限切れの削除で消え続けたら、3 回でやめて例外にする") {
            val jdbc = FakeJdbc()
            shouldThrow<IllegalStateException> { jdbc.store().claim(REQUEST, 1.minutes) }
            jdbc.executed.count { it.sql.contains("INSERT") } shouldBe 3
        }
    })
