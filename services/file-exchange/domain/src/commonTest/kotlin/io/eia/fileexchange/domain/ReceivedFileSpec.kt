package io.eia.fileexchange.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ReceivedFileSpec :
    FunSpec({
        val archived =
            ReceivedFile(
                "sales_daily_20261014150000_001",
                "a".repeat(64),
                ReceiptDestination.ARCHIVE,
                "x.parquet",
                null,
                null,
                ReceiptState.STORED,
            )

        test("合格はデータファイルの名前を持ち、理由を持たない。不合格は理由を持つ") {
            shouldThrow<IllegalArgumentException> { archived.copy(reason = "checksum_mismatch") }
            shouldThrow<IllegalArgumentException> { archived.copy(dataFile = null) }
            shouldThrow<IllegalArgumentException> { archived.copy(destination = ReceiptDestination.QUARANTINE) }
            archived
                .copy(
                    destination = ReceiptDestination.QUARANTINE,
                    dataFile = null,
                    reason = "data_missing",
                    detail = "なし",
                ).state shouldBe
                ReceiptState.STORED
        }

        test("受け取り済みの名前で届いたファイル: 同じ SHA-256 なら送り直し、違うか分からなければ名前の使い回し") {
            ExtraArrivalReason.of(archived, "a".repeat(64)) shouldBe ExtraArrivalReason.DUPLICATE
            ExtraArrivalReason.of(archived, "b".repeat(64)) shouldBe ExtraArrivalReason.NAME_REUSED
            ExtraArrivalReason.of(archived, null) shouldBe ExtraArrivalReason.NAME_REUSED
            ExtraArrivalReason.of(archived.copy(sha256 = null), null) shouldBe ExtraArrivalReason.NAME_REUSED
            ExtraArrivalReason.DUPLICATE.code shouldBe "duplicate"
        }

        test("受け取り済みの名前で届いたファイルの記録は、番号・名前・SHA-256・理由を持つ") {
            val arrival = ExtraArrival(3, archived.name, null, ExtraArrivalReason.NAME_REUSED)
            arrival.copy(sha256 = "a".repeat(64)).sha256 shouldBe "a".repeat(64)
            arrival.reason.code shouldBe "name_reused"
            arrival.id shouldBe 3
            ReceiptDestination.entries.map { it.name } shouldBe listOf("ARCHIVE", "QUARANTINE")
            ReceiptState.entries.map { it.name } shouldBe listOf("MOVING", "STORED")
        }
    })
