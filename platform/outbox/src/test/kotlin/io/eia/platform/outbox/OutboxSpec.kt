package io.eia.platform.outbox

import io.eia.shared.kernel.DomainError
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
import kotlin.uuid.Uuid

private class RecordingListener : OutboxListener {
    val appended = mutableListOf<List<OutboxRecord>>()
    val failed = mutableListOf<OutboxError>()

    override fun appended(records: List<OutboxRecord>) {
        appended += records
    }

    override fun failed(error: OutboxError) {
        failed += error
    }
}

/** 自動コミットが無効な接続。INSERT は [insertFailure] があれば投げ、DELETE は [deleted] 行を返す。 */
private fun connection(
    deleted: Int,
    insertFailure: SQLException? = null,
    autoCommit: Boolean = false,
): Connection {
    val insert = mockk<PreparedStatement>(relaxed = true)
    if (insertFailure != null) every { insert.executeBatch() } throws insertFailure
    val delete = mockk<PreparedStatement>(relaxed = true)
    every { delete.executeUpdate() } returns deleted
    return mockk(relaxed = true) {
        every { this@mockk.autoCommit } returns autoCommit
        every { prepareStatement(match { it.startsWith("INSERT") }) } returns insert
        every { prepareStatement(match { it.startsWith("DELETE") }) } returns delete
    }
}

class OutboxSpec :
    FunSpec({
        test("INSERT と、同じ行の DELETE を同じ接続(トランザクション)で行い、成功をリスナーに知らせる") {
            val listener = RecordingListener()
            val connection = connection(deleted = 2)
            val records = listOf(record(), record(Uuid.parse("0199b6a0-0000-7000-8000-000000000002")))

            Outbox(listener).append(connection, records).ok()

            verify(exactly = 1) { connection.prepareStatement(match { it.startsWith("INSERT INTO outbox.outbox") }) }
            verify(exactly = 1) { connection.prepareStatement("DELETE FROM outbox.outbox WHERE id = ANY(?)") }
            listener.appended shouldBe listOf(records)
        }

        test("空なら何もしない") {
            val connection = mockk<Connection>()
            Outbox().append(connection, emptyList()).ok()
            verify(exactly = 0) { connection.prepareStatement(any()) }
        }

        test("自動コミットが有効(トランザクションの外)なら書かずに OutboxMisuse") {
            val listener = RecordingListener()
            val connection = connection(deleted = 1, autoCommit = true)

            Outbox(listener).append(connection, listOf(record())).err().shouldBeInstanceOf<OutboxMisuse>()
            verify(exactly = 0) { connection.prepareStatement(any()) }
            listener.failed.single().code shouldBe "outbox_misuse"
        }

        test("同じ ID のイベントを 2 つ含めば OutboxMisuse") {
            Outbox().append(connection(deleted = 2), listOf(record(), record())).err().shouldBeInstanceOf<OutboxMisuse>()
        }

        test("SQLSTATE で分類する: 接続(08)・ロールバック(40)は Retryable、制約違反・権限は NonRetryable。理由にドライバのメッセージを入れない") {
            fun failure(state: String) =
                Outbox()
                    .append(
                        connection(deleted = 1, insertFailure = SQLException("detail: secret-row-value", state)),
                        listOf(record()),
                    ).err()

            failure("08006") shouldBe OutboxStorageUnavailable("SQLSTATE 08006")
            failure("40001").asDomainError().shouldBeInstanceOf<DomainError.Retryable>()
            val rejected = failure("23505")
            rejected shouldBe OutboxStorageRejected("SQLSTATE 23505")
            rejected.asDomainError().shouldBeInstanceOf<DomainError.NonRetryable>()
            failure("42501").message shouldNotContain "secret-row-value"
        }

        test("DELETE した行の数が合わなければ OutboxStorageRejected(取り消してもらう)") {
            Outbox().append(connection(deleted = 0), listOf(record())).err().shouldBeInstanceOf<OutboxStorageRejected>()
        }

        test("OutboxRecord: ce_type とトピックの食い違い・空の値を拒否し、toString にペイロードを出さない") {
            shouldThrow<IllegalArgumentException> {
                OutboxRecord(
                    TOPIC,
                    "parcel",
                    "p-1",
                    metadata(type = "test.parcel.lost"),
                    byteArrayOf(1),
                )
            }
            shouldThrow<IllegalArgumentException> { OutboxRecord(TOPIC, "", "p-1", metadata(), byteArrayOf(1)) }
            shouldThrow<IllegalArgumentException> { OutboxRecord(TOPIC, "parcel", "p-1", metadata(), byteArrayOf()) }
            record().toString() shouldBe
                "OutboxRecord(topic=test.parcel.shipped.v1, aggregateType=parcel, aggregateId=p-1, " +
                "id=0199b6a0-0000-7000-8000-000000000001, payload=7 bytes)"
        }
    })
