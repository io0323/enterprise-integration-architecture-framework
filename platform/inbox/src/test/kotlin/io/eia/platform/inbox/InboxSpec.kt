package io.eia.platform.inbox

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private fun <E> Result<*, E>.err(): E =
    when (this) {
        is Result.Ok -> fail("Err を期待しましたが Ok でした: $value")
        is Result.Err -> error
    }

private val ID = Uuid.parse("0199b6a0-0000-7000-8000-000000000001")

/** 自動コミットが無効な接続。INSERT は [inserted] 行を返すか [failure] を投げる。 */
private fun connection(
    inserted: Int = 1,
    failure: SQLException? = null,
    autoCommit: Boolean = false,
): Pair<Connection, PreparedStatement> {
    val statement = mockk<PreparedStatement>(relaxed = true)
    if (failure != null) every { statement.executeUpdate() } throws failure else every { statement.executeUpdate() } returns inserted
    val connection =
        mockk<Connection>(relaxed = true) {
            every { this@mockk.autoCommit } returns autoCommit
            every { prepareStatement(any()) } returns statement
        }
    return connection to statement
}

class InboxSpec :
    FunSpec({
        test("挿入できれば FIRST、主キーが重複して挿入されなければ DUPLICATE") {
            Inbox().markProcessed(connection(inserted = 1).first, "inventory.command", ID, "t.a.b.v1").ok() shouldBe Inbox.Receipt.FIRST
            Inbox().markProcessed(connection(inserted = 0).first, "inventory.command", ID, "t.a.b.v1").ok() shouldBe
                Inbox.Receipt.DUPLICATE
        }

        test("自動コミットの接続には書かない(業務と別に確定すると、失敗した処理が重複として捨てられる)") {
            val (connection, statement) = connection(autoCommit = true)
            Inbox().markProcessed(connection, "inventory.command", ID, "t.a.b.v1").err().shouldBeInstanceOf<InboxMisuse>()
            verify(exactly = 0) { statement.executeUpdate() }
        }

        test("Consumer Group の形式({service}.{purpose})とトピックの空を、SQL の前に拒否する") {
            listOf("inventory", "Inventory.command", "inventory.", "inventory.command.extra").forEach { group ->
                Inbox().markProcessed(connection().first, group, ID, "t.a.b.v1").err().shouldBeInstanceOf<InboxMisuse>()
            }
            Inbox().markProcessed(connection().first, "inventory.command", ID, " ").err().shouldBeInstanceOf<InboxMisuse>()
        }

        test("SQLSTATE のクラスで分類し、理由に SQLSTATE だけを入れる(ドライバのメッセージは入れない)") {
            val transient = SQLException("connection refused to 10.0.0.1 secret", "08006")
            val unavailable =
                Inbox().markProcessed(connection(failure = transient).first, "inventory.command", ID, "t.a.b.v1").err()
            unavailable.shouldBeInstanceOf<InboxStorageUnavailable>()
            unavailable.asDomainError().shouldBeInstanceOf<DomainError.Retryable>()
            unavailable.message shouldNotContain "secret"

            val denied = SQLException("permission denied", "42501")
            val rejected = Inbox().markProcessed(connection(failure = denied).first, "inventory.command", ID, "t.a.b.v1").err()
            rejected.shouldBeInstanceOf<InboxStorageRejected>()
            rejected.asDomainError().shouldBeInstanceOf<DomainError.NonRetryable>()

            Inbox.classify(SQLException("no state")).shouldBeInstanceOf<InboxStorageUnavailable>()
            Inbox().markProcessed(connection(failure = transient).first, "inventory.command", ID, "t.a.b.v1").err().code shouldBe
                "inbox_storage_unavailable"
        }

        test("purgeExpired は保持期間を秒で、件数の上限とともに渡し、消した件数を返す") {
            val (connection, statement) = connection(inserted = 3)
            Inbox().purgeExpired(connection, 2.days, batchSize = 10).ok() shouldBe 3
            verify { statement.setLong(1, 2.days.inWholeSeconds) }
            verify { statement.setInt(2, 10) }

            val failing = connection(failure = SQLException("x", "57P01")).first
            Inbox().purgeExpired(failing).err().shouldBeInstanceOf<InboxStorageUnavailable>()
        }

        test("purgeExpired の引数の誤りは例外(呼び出し側の設定の誤り)") {
            shouldThrow<IllegalArgumentException> { Inbox().purgeExpired(connection().first, Duration.ZERO) }
            shouldThrow<IllegalArgumentException> { Inbox().purgeExpired(connection().first, batchSize = 0) }
        }

        test("既定の保持期間は、トピックと DLQ の保持期間(7 日)より長い(ADR-0028 §3)") {
            (Inbox.DEFAULT_RETENTION > 7.days) shouldBe true
        }

        test("マイグレーションのロールの名前の形式を、接続の前に拒否する") {
            InboxSchema.migrate(mockk(), "App-Role").err().shouldBeInstanceOf<InboxMisuse>()
        }
    })
