package io.eia.order.adapters.out.persistence

import io.eia.platform.api.idempotency.ClaimResult
import io.eia.platform.api.idempotency.IdempotencyRequest
import io.eia.platform.api.idempotency.IdempotencyScope
import io.eia.platform.api.idempotency.Lease
import io.eia.platform.api.idempotency.RequestFingerprint
import io.eia.platform.api.idempotency.StoredResponse
import io.eia.shared.kernel.IdempotencyKey
import io.eia.shared.kernel.Result
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.sql.Connection
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private val SCOPE = IdempotencyScope("client-a", (IdempotencyKey.parse("key-1") as Result.Ok).value)
private val FINGERPRINT = RequestFingerprint.of("POST", "/v1/orders", "application/json", """{"sku":"A"}""".toByteArray())
private val REQUEST = IdempotencyRequest(SCOPE, FINGERPRINT)
private val RESPONSE =
    StoredResponse(201, listOf("Content-Type" to "application/json", "Set" to "a", "Set" to "b"), byteArrayOf(0x00, 0xFF.toByte()))

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

class PostgresIdempotencyStoreSpec :
    FunSpec({
        context("claim") {
            test("RETURNING で自分のトークンが返れば Acquired。範囲・指紋・トークン・リース(ミリ秒)を渡す") {
                val jdbc =
                    FakeJdbc().apply {
                        rows =
                            { sql, params -> if (sql.contains("INSERT")) listOf(mapOf("lease_token" to params[4])) else emptyList() }
                    }
                val lease =
                    jdbc
                        .store()
                        .claim(REQUEST, 1.minutes)
                        .shouldBeInstanceOf<ClaimResult.Acquired>()
                        .lease

                val params = jdbc.executed.single().params
                params shouldBe mapOf(1 to "client-a", 2 to "key-1", 3 to FINGERPRINT.value, 4 to lease.token, 5 to 60_000L)
                lease.scope shouldBe SCOPE
            }

            test("引き継げなければ既存の記録を読む: 完了は保存した応答(ヘッダの順序と重複、バイト列の本文)") {
                val jdbc =
                    FakeJdbc().apply {
                        rows = { sql, _ ->
                            if (sql.contains("SELECT state")) {
                                listOf(
                                    mapOf(
                                        "state" to "COMPLETED",
                                        "fingerprint" to FINGERPRINT.value,
                                        "response_status" to 201,
                                        "response_headers" to """[["Content-Type","application/json"],["Set","a"],["Set","b"]]""",
                                        "response_body" to byteArrayOf(0x00, 0xFF.toByte()),
                                        "lease_remaining_ms" to 0L,
                                    ),
                                )
                            } else {
                                emptyList()
                            }
                        }
                    }
                jdbc.store().claim(REQUEST, 1.minutes) shouldBe ClaimResult.Completed(FINGERPRINT, RESPONSE)
            }

            test("処理中の既存の記録は、DB で測ったリースの残り時間を返す") {
                val other = RequestFingerprint("0".repeat(64))
                val jdbc =
                    FakeJdbc().apply {
                        rows = { sql, _ ->
                            if (sql.contains("SELECT state")) {
                                listOf(mapOf("state" to "IN_PROGRESS", "fingerprint" to other.value, "lease_remaining_ms" to 1_500L))
                            } else {
                                emptyList()
                            }
                        }
                    }
                jdbc.store().claim(REQUEST, 1.minutes) shouldBe ClaimResult.InProgress(other, 1_500.milliseconds)
            }

            test("読む前に記録が消え続けたら、3 回でやめて例外にする") {
                val jdbc = FakeJdbc()
                shouldThrow<IllegalStateException> { jdbc.store().claim(REQUEST, 1.minutes) }
                jdbc.executed.count { it.sql.contains("INSERT") } shouldBe 3
            }
        }

        context("complete / release / purgeExpired") {
            val lease = Lease(SCOPE, "token-1")

            test("complete は応答(ヘッダは [名前, 値] の配列の JSON)・保持期間(ミリ秒)・範囲・トークンを渡し、1 件の更新なら true") {
                val jdbc = FakeJdbc()
                jdbc.store().complete(lease, RESPONSE, 24.hours) shouldBe true

                val params = jdbc.executed.single().params
                params[1] shouldBe 201
                params[2] shouldBe """[["Content-Type","application/json"],["Set","a"],["Set","b"]]"""
                (params[3] as ByteArray).toList() shouldBe RESPONSE.body.toList()
                params.filterKeys { it >= 4 } shouldBe mapOf(4 to 86_400_000L, 5 to "client-a", 6 to "key-1", 7 to "token-1")
            }

            test("complete でトークンが一致しなければ(0 件)false") {
                val jdbc = FakeJdbc().apply { updated = { 0 } }
                jdbc.store().complete(lease, RESPONSE, 24.hours) shouldBe false
            }

            test("release は範囲とトークンを渡す") {
                val jdbc = FakeJdbc()
                jdbc.store().release(lease)
                jdbc.executed.single().params shouldBe mapOf(1 to "client-a", 2 to "key-1", 3 to "token-1")
            }

            test("purgeExpired は猶予(ミリ秒)を渡し、消した件数を返す") {
                val jdbc = FakeJdbc().apply { updated = { 7 } }
                jdbc.store().purgeExpired(1.minutes) shouldBe 7
                jdbc.executed.single().params shouldBe mapOf(1 to 60_000L)
            }
        }
    })
