package io.eia.fileexchange.application

import io.eia.fileexchange.application.port.outbound.FileLocks
import io.eia.fileexchange.application.port.outbound.Inbox
import io.eia.fileexchange.application.port.outbound.Inspection
import io.eia.fileexchange.application.port.outbound.ReceivedFiles
import io.eia.fileexchange.application.port.outbound.WaitingFile
import io.eia.fileexchange.application.usecase.ReceiveFileService
import io.eia.fileexchange.application.usecase.SweepInboxService
import io.eia.fileexchange.domain.ExtraArrival
import io.eia.fileexchange.domain.ExtraArrivalReason
import io.eia.fileexchange.domain.ReceiptDestination
import io.eia.fileexchange.domain.ReceiptState
import io.eia.fileexchange.domain.ReceivedFile
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant

private fun <T, E> Result<T, E>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private const val NAME = "sales_daily_20261014150000_001"
private val SHA = "a".repeat(64)
private val FAILURE = UnavailableError("置き場を使えません")

/** 受け口の代わり。呼ばれた操作を記録する。 */
private class FakeInbox : Inbox {
    var inspection: Inspection = Inspection.Accepted(SHA, "$NAME.parquet")
    val waitingFiles = mutableMapOf<String, WaitingFile>()
    val calls = mutableListOf<String>()
    var failing: String? = null

    private fun call(name: String): Result<Unit, DomainError> {
        calls += name
        return if (failing == name.substringBefore(' ')) err(FAILURE) else ok(Unit)
    }

    override suspend fun inspect(name: String): Result<Inspection, DomainError> =
        call("inspect $name").let {
            if (it is Result.Err) it else ok(inspection)
        }

    override suspend fun archive(
        name: String,
        dataFile: String,
    ) = call("archive $name $dataFile")

    override suspend fun quarantine(
        name: String,
        reason: String,
        detail: String,
    ) = call("quarantine $name $reason")

    override suspend fun quarantineExtra(arrival: ExtraArrival) =
        call("extra ${arrival.name} ${arrival.id} ${arrival.reason.code}").also {
            waitingFiles.remove(arrival.name)
        }

    override suspend fun waiting(): Result<List<WaitingFile>, DomainError> =
        if (failing == "waiting") err(FAILURE) else ok(waitingFiles.values.sortedBy { it.name })

    override suspend fun waiting(name: String): Result<WaitingFile?, DomainError> = ok(waitingFiles[name])
}

/** 受け取りの記録の代わり(一意の制約も同じに振る舞う)。 */
private class FakeFiles : ReceivedFiles {
    val records = mutableMapOf<String, ReceivedFile>()
    val extras = mutableMapOf<Long, Pair<ExtraArrival, Boolean>>()
    var failing = false

    private fun <T> guard(block: () -> T): Result<T, DomainError> = if (failing) err(FAILURE) else ok(block())

    override suspend fun find(name: String) = guard { records[name] }

    override suspend fun begin(file: ReceivedFile) = guard { records.getOrPut(file.name) { file } }

    override suspend fun stored(name: String) = guard { records[name] = records.getValue(name).copy(state = ReceiptState.STORED) }

    override suspend fun beginExtra(
        name: String,
        sha256: String?,
        reason: ExtraArrivalReason,
    ) = guard {
        extras.values.firstOrNull { (arrival, done) -> arrival.name == name && !done }?.first
            ?: ExtraArrival(extras.size + 1L, name, sha256, reason).also { extras[it.id] = it to false }
    }

    override suspend fun movingExtras() = guard { extras.values.filter { !it.second }.map { it.first } }

    override suspend fun finishExtra(id: Long) = guard { extras[id] = extras.getValue(id).first to true }
}

/** ロックの代わり。[busy] の名前はほかが持っている。 */
private class FakeLocks : FileLocks {
    val busy = mutableSetOf<String>()

    override suspend fun <T> withLock(
        name: String,
        block: suspend () -> Result<T, DomainError>,
    ): Result<T?, DomainError> = if (name in busy) ok(null) else block()
}

private fun stored(sha256: String? = SHA) =
    ReceivedFile(NAME, sha256, ReceiptDestination.ARCHIVE, "$NAME.parquet", null, null, ReceiptState.STORED)

class FileExchangeUseCasesSpec :
    FunSpec({
        context("受け取り") {
            test("合格: 検査 → 移動の決定(移動中)を記録 → archive → 済み") {
                val inbox = FakeInbox()
                val files = FakeFiles()
                val outcome = ReceiveFileService(inbox, files)(NAME).ok()

                inbox.calls shouldBe listOf("inspect $NAME", "archive $NAME $NAME.parquet")
                outcome.resumed shouldBe false
                outcome.alreadyStored shouldBe false
                outcome.file.state shouldBe ReceiptState.STORED
                files.records.getValue(NAME) shouldBe stored()
            }

            test("不合格: quarantine に理由と一緒に移す") {
                val inbox = FakeInbox().apply { inspection = Inspection.Rejected("checksum_mismatch", "SHA-256 が違います", SHA) }
                val files = FakeFiles()
                ReceiveFileService(inbox, files)(NAME).ok().file.destination shouldBe ReceiptDestination.QUARANTINE
                inbox.calls shouldBe listOf("inspect $NAME", "quarantine $NAME checksum_mismatch")
                files.records.getValue(NAME).reason shouldBe "checksum_mismatch"
            }

            test("記録が移動中(移動の途中で落ちた)なら、検査をせずに記録した移動を続ける") {
                val inbox = FakeInbox().apply { inspection = Inspection.Rejected("data_missing", "なし", SHA) }
                val files = FakeFiles().apply { records[NAME] = stored().copy(state = ReceiptState.MOVING) }
                val outcome = ReceiveFileService(inbox, files)(NAME).ok()
                inbox.calls shouldBe listOf("archive $NAME $NAME.parquet")
                outcome.resumed shouldBe true
                files.records.getValue(NAME).state shouldBe ReceiptState.STORED
            }

            test("移し終えた記録があれば何もしない。先に記録した決定に従う") {
                val inbox = FakeInbox()
                val files = FakeFiles().apply { records[NAME] = stored() }
                ReceiveFileService(inbox, files)(NAME).ok().alreadyStored shouldBe true
                inbox.calls.shouldBeEmpty()

                // 検査の後、記録の前にほかが記録した: その決定(quarantine)に従う
                val raced =
                    object : ReceivedFiles by FakeFiles() {
                        override suspend fun find(name: String): Result<ReceivedFile?, DomainError> = ok(null)

                        override suspend fun begin(file: ReceivedFile) =
                            ok(ReceivedFile(NAME, SHA, ReceiptDestination.QUARANTINE, null, "size_mismatch", "違う", ReceiptState.MOVING))

                        override suspend fun stored(name: String): Result<Unit, DomainError> = ok(Unit)
                    }
                val inbox2 = FakeInbox()
                ReceiveFileService(inbox2, raced)(NAME).ok().file.reason shouldBe "size_mismatch"
                inbox2.calls shouldBe listOf("inspect $NAME", "quarantine $NAME size_mismatch")
            }

            test("記録・検査・移動の失敗はそのまま返す(記録は移動中のまま残り、やり直しで続きから)") {
                (ReceiveFileService(FakeInbox(), FakeFiles().apply { failing = true })(NAME) is Result.Err) shouldBe true
                (ReceiveFileService(FakeInbox().apply { failing = "inspect" }, FakeFiles())(NAME) is Result.Err) shouldBe true
                val files = FakeFiles()
                (ReceiveFileService(FakeInbox().apply { failing = "archive" }, files)(NAME) is Result.Err) shouldBe true
                files.records.getValue(NAME).state shouldBe ReceiptState.MOVING
            }
        }

        context("見回り") {
            val created = Instant.parse("2026-10-14T16:00:00Z")

            test("受け取り済みの名前で届いたファイル: 同じ SHA-256 は送り直し、違えば名前の使い回しとして移す") {
                val inbox =
                    FakeInbox().apply {
                        waitingFiles[NAME] = WaitingFile(NAME, SHA, created)
                        waitingFiles["other_x_20261014150000_001"] = WaitingFile("other_x_20261014150000_001", "b".repeat(64), created)
                    }
                val files =
                    FakeFiles().apply {
                        records[NAME] = stored()
                        records["other_x_20261014150000_001"] = stored().copy(name = "other_x_20261014150000_001")
                    }
                val report = SweepInboxService(inbox, files, FakeLocks())().ok()

                report.moved.map { it.name to it.reason } shouldBe
                    listOf("other_x_20261014150000_001" to ExtraArrivalReason.NAME_REUSED, NAME to ExtraArrivalReason.DUPLICATE)
                report.waiting shouldBe 0
                files.extras.values.all { it.second } shouldBe true
            }

            test("まだ受け取っていない名前・移動中の名前・ロックを取れない名前には触れない。残っているものの最も古い時刻を返す") {
                val inbox =
                    FakeInbox().apply {
                        waitingFiles["fresh_a_20261014150000_001"] = WaitingFile("fresh_a_20261014150000_001", SHA, created)
                        waitingFiles[NAME] = WaitingFile(NAME, SHA, created.minusSeconds())
                        waitingFiles["busy_b_20261014150000_001"] = WaitingFile("busy_b_20261014150000_001", SHA, null)
                    }
                val files =
                    FakeFiles().apply {
                        records[NAME] = stored().copy(state = ReceiptState.MOVING)
                        records["busy_b_20261014150000_001"] = stored().copy(name = "busy_b_20261014150000_001")
                    }
                val locks = FakeLocks().apply { busy += "busy_b_20261014150000_001" }
                val report = SweepInboxService(inbox, files, locks)().ok()

                report.moved.shouldBeEmpty()
                report.waiting shouldBe 3
                report.oldestCreatedAt shouldBe created.minusSeconds()
                inbox.calls.shouldBeEmpty()
            }

            test("一覧の後に受け取りの実行が移し終えた(ロックの中で読み直すとマニフェストがない)なら、触れない") {
                val inbox =
                    object : Inbox by FakeInbox() {
                        override suspend fun waiting(): Result<List<WaitingFile>, DomainError> = ok(listOf(WaitingFile(NAME, SHA, created)))

                        override suspend fun waiting(name: String): Result<WaitingFile?, DomainError> = ok(null)

                        override suspend fun quarantineExtra(arrival: ExtraArrival): Result<Unit, DomainError> = fail("移してはいけません")
                    }
                SweepInboxService(inbox, FakeFiles().apply { records[NAME] = stored() }, FakeLocks())().ok().moved.shouldBeEmpty()
            }

            test("処理中のまま残っているもの(前の見回りが落ちた)を先に終える") {
                val inbox = FakeInbox()
                val files = FakeFiles().apply { extras[7] = ExtraArrival(7, NAME, SHA, ExtraArrivalReason.DUPLICATE) to false }
                SweepInboxService(inbox, files, FakeLocks())()
                    .ok()
                    .moved
                    .single()
                    .id shouldBe 7
                inbox.calls shouldBe listOf("extra $NAME 7 duplicate")
                files.extras.getValue(7).second shouldBe true
            }

            test("失敗はそのまま返す") {
                (SweepInboxService(FakeInbox(), FakeFiles().apply { failing = true }, FakeLocks())() is Result.Err) shouldBe true
                SweepInboxService(FakeInbox().apply { failing = "waiting" }, FakeFiles(), FakeLocks())()
                    .shouldBeInstanceOf<Result.Err<DomainError>>()
                val inbox =
                    FakeInbox().apply {
                        waitingFiles[NAME] = WaitingFile(NAME, SHA, created)
                        failing = "extra"
                    }
                (SweepInboxService(inbox, FakeFiles().apply { records[NAME] = stored() }, FakeLocks())() is Result.Err) shouldBe true
            }
        }
    })

private fun Instant.minusSeconds(): Instant = Instant.fromEpochSeconds(epochSeconds - 60)
