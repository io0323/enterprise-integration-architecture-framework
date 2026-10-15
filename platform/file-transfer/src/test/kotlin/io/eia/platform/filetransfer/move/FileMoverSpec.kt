package io.eia.platform.filetransfer.move

import io.eia.platform.filetransfer.FileTransferMisuse
import io.eia.platform.filetransfer.Fixtures
import io.eia.platform.filetransfer.InMemoryObjectStore
import io.eia.platform.filetransfer.MoveIncomplete
import io.eia.platform.filetransfer.StoreUnavailable
import io.eia.platform.filetransfer.err
import io.eia.platform.filetransfer.layout.FileFormat
import io.eia.platform.filetransfer.ok
import io.eia.platform.filetransfer.verify.FileVerifier
import io.eia.platform.filetransfer.verify.RejectionReason
import io.eia.platform.filetransfer.verify.Verification
import io.eia.platform.filetransfer.write.FilePublisher
import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlin.time.Instant

class FileMoverSpec :
    FunSpec({
        val workDir = Files.createTempDirectory("file-mover-spec")
        val base = "sales_daily_20261014160000_001"
        val data = "$base.parquet"
        val manifest = "$base.manifest.json"
        val reason =
            QuarantineReason(RejectionReason.CHECKSUM_MISMATCH, "SHA-256 がマニフェストと違います", Instant.parse("2026-10-15T00:00:00Z"), "corr-1")

        afterSpec { workDir.toFile().deleteRecursively() }

        fun published(): InMemoryObjectStore =
            InMemoryObjectStore().also { FilePublisher(it, workDir).publish(Fixtures.request(FileFormat.PARQUET), Fixtures.rows(3)).ok() }

        fun archive(store: InMemoryObjectStore) = FileMover(store).archive(Fixtures.INBOX, base, data, Fixtures.ARCHIVE)

        fun quarantine(store: InMemoryObjectStore) = FileMover(store).quarantine(Fixtures.INBOX, base, Fixtures.QUARANTINE, reason)

        test("archive: データファイル → マニフェストの順にコピーしてから消す") {
            val store = published()
            val before = store.objects.toMap()

            archive(store).ok()

            store.keys() shouldContainExactly listOf("archive/$manifest", "archive/$data")
            store.objects.getValue("archive/$data") shouldBe before.getValue("inbox/$data")
            // コピーと削除を 2 つずつ(データファイル・マニフェストの順)
            store.mutations shouldBe 2 + 4
        }

        test("quarantine: 理由のファイルを置く(値は入れない)。データファイルがなければ(欠損)マニフェストと理由だけ") {
            val store = published()
            quarantine(store).ok()
            store.keys() shouldContainExactly listOf("quarantine/$manifest", "quarantine/$data", "quarantine/$base.reason.json")
            val json = store.objects.getValue("quarantine/$base.reason.json").decodeToString()
            json shouldContain "\"reason\":\"checksum_mismatch\""
            json shouldContain "\"correlationId\":\"corr-1\""

            val missing = published().also { it.objects.remove("inbox/$data") }
            quarantine(missing).ok()
            missing.keys() shouldContainExactly listOf("quarantine/$manifest", "quarantine/$base.reason.json")
        }

        test("archive: どの手順の間で落ちても、同じ移動をもう一度呼べば archive に収まる(ADR-0032 §4)") {
            val total = published().let { store -> store.mutations.let { before -> archive(store).ok().let { store.mutations - before } } }
            total shouldBe 4
            for (crashAt in 1..total) {
                val store = published()
                store.crashAt = store.mutations + crashAt
                archive(store).err() shouldBe StoreUnavailable("crash")

                store.crashAt = null
                archive(store).ok()
                store.keys() shouldContainExactly listOf("archive/$manifest", "archive/$data")
            }
        }

        test("quarantine: どの手順の間で落ちても、同じ移動をもう一度呼べば quarantine に収まる") {
            val total =
                published().let { store ->
                    store.mutations.let { before -> quarantine(store).ok().let { store.mutations - before } }
                }
            total shouldBe 5
            for (crashAt in 1..total) {
                val store = published()
                store.crashAt = store.mutations + crashAt
                quarantine(store).err() shouldBe StoreUnavailable("crash")

                store.crashAt = null
                quarantine(store).ok()
                store.keys() shouldContainExactly listOf("quarantine/$manifest", "quarantine/$data", "quarantine/$base.reason.json")
            }
        }

        test("データファイルを移した後・マニフェストを移す前に落ちると、受け口はマニフェストだけ。検査からやり直すと欠損と判定してしまう") {
            val store = published()
            // 手順: 1 データファイルのコピー、2 データファイルの削除、3 マニフェストのコピー → 3 の直前で落とす
            store.crashAt = store.mutations + 3
            archive(store).err() shouldBe StoreUnavailable("crash")
            store.keys() shouldContainExactly listOf("archive/$data", "inbox/$manifest")

            // 検査からやり直すと、データファイルの欠損として quarantine に送ってしまう(だから呼び出し側は移動の決定を先に記録する。ADR-0032 §5)
            store.crashAt = null
            val again = FileVerifier(store, listOf(Fixtures.LAYOUT), workDir).verify(Fixtures.INBOX, "inbox/$manifest").ok()
            again.shouldBeInstanceOf<Verification.Rejected>().reason shouldBe RejectionReason.DATA_MISSING

            // 記録した決定(archive)の移動を呼び直せば、archive に収まる
            archive(store).ok()
            store.keys() shouldContainExactly listOf("archive/$manifest", "archive/$data")
        }

        test("移動が済んだ後にもう一度呼んでも同じ結果。移動元にも移動先にもなければ、続けられない") {
            val store = published()
            archive(store).ok()
            archive(store).ok()
            store.keys() shouldContainExactly listOf("archive/$manifest", "archive/$data")

            val empty = InMemoryObjectStore()
            archive(empty).err().shouldBeInstanceOf<MoveIncomplete>()
            // archive はデータファイルが要る(欠損のファイルは quarantine に送る)
            val missing = published().also { it.objects.remove("inbox/$data") }
            archive(missing).err().shouldBeInstanceOf<MoveIncomplete>().message shouldContain "archive/$data"
        }

        test("使い方の誤り: データファイルの名前が base と対応しない・移動元と移動先が同じ") {
            val store = published()
            FileMover(store).archive(Fixtures.INBOX, base, "other.parquet", Fixtures.ARCHIVE).err().shouldBeInstanceOf<FileTransferMisuse>()
            FileMover(store).archive(Fixtures.INBOX, base, data, Fixtures.INBOX).err().shouldBeInstanceOf<FileTransferMisuse>()
            store.failing = StoreUnavailable("SlowDown")
            (archive(store) is Result.Err) shouldBe true
        }
    })
