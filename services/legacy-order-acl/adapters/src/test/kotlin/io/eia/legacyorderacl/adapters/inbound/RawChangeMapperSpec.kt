package io.eia.legacyorderacl.adapters.inbound

import io.eia.legacyorderacl.application.port.inbound.LegacyOrderChange
import io.eia.legacyorderacl.domain.TranslationError
import io.eia.legacyorderacl.domain.TranslationFailure
import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant

private fun row(number: String) = RawJuchuRow("1", number, "1", "山田".padEnd(40), "C0000101", "1200.00", 0L, 0L)

private fun source(
    lsn: Long? = 26_000_000L,
    snapshot: String? = "false",
    micros: Long? = 1_791_454_000_123_456L,
) = RawDebeziumSource(snapshot = snapshot, lsn = lsn, committedAtMillis = 1_791_454_000_123L, committedAtMicros = micros)

class RawChangeMapperSpec :
    FunSpec({
        test("c / u / r は変更の後の行の Upsert。位置は LSN・コミットの時刻(マイクロ秒)・Snapshot かどうか") {
            listOf("c", "u", "r").forEach { op ->
                val change = RawChangeMapper.toChange(RawJuchuEnvelope(after = row("J1"), source = source(), op = op))
                val upsert = change.shouldBeInstanceOf<Result.Ok<LegacyOrderChange>>().value.shouldBeInstanceOf<LegacyOrderChange.Upsert>()
                upsert.row.orderNumber shouldBe "J1"
                upsert.position.lsn shouldBe 26_000_000L
                upsert.position.committedAt shouldBe Instant.parse("2026-10-08T10:06:40.123456Z")
                upsert.position.snapshot shouldBe false
            }
        }

        test("d は変更の前の行の注文番号の Delete") {
            val change = RawChangeMapper.toChange(RawJuchuEnvelope(before = row("J2"), source = source(), op = "d"))
            change.shouldBeInstanceOf<Result.Ok<LegacyOrderChange>>().value shouldBe
                LegacyOrderChange.Delete("J2", (change.value as LegacyOrderChange.Delete).position)
        }

        test("Snapshot の印(true / first / last / incremental など)は snapshot=true。ts_us がなければ ts_ms を使う") {
            listOf("true", "first", "last", "incremental", "last_in_data_collection").forEach { mark ->
                val change =
                    RawChangeMapper.toChange(
                        RawJuchuEnvelope(after = row("J3"), source = source(snapshot = mark, micros = null), op = "r"),
                    )
                val position = (change as Result.Ok).value.position
                position.snapshot shouldBe true
                position.committedAt shouldBe Instant.parse("2026-10-08T10:06:40.123Z")
            }
            val streamed = RawChangeMapper.toChange(RawJuchuEnvelope(after = row("J3"), source = source(snapshot = null), op = "c"))
            (streamed as Result.Ok).value.position.snapshot shouldBe false
        }

        test("形が合わない(未知の op・行がない・LSN がない)ものは UNDECODABLE") {
            listOf(
                RawJuchuEnvelope(after = row("J4"), source = source(), op = "t"),
                RawJuchuEnvelope(source = source(), op = "c"),
                RawJuchuEnvelope(after = row("J4"), source = source(), op = "d"),
                RawJuchuEnvelope(after = row("J4"), source = source(lsn = null), op = "c"),
            ).forEach { envelope ->
                RawChangeMapper
                    .toChange(envelope)
                    .shouldBeInstanceOf<Result.Err<TranslationError>>()
                    .error.failure shouldBe TranslationFailure.UNDECODABLE
            }
        }
    })
