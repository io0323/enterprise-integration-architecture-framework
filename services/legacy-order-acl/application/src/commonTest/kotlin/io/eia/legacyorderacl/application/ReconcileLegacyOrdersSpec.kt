package io.eia.legacyorderacl.application

import io.eia.legacyorderacl.application.port.inbound.Mismatch
import io.eia.legacyorderacl.application.port.inbound.ReconciliationReport
import io.eia.legacyorderacl.application.port.outbound.LegacySource
import io.eia.legacyorderacl.application.port.outbound.PublishedLegacyOrders
import io.eia.legacyorderacl.application.port.outbound.ReconcileScope
import io.eia.legacyorderacl.application.port.outbound.SourceSnapshot
import io.eia.legacyorderacl.application.usecase.ReconcileLegacyOrdersService
import io.eia.legacyorderacl.domain.LegacyOrder
import io.eia.legacyorderacl.domain.LegacyOrderRow
import io.eia.legacyorderacl.domain.LegacyOrderStatus
import io.eia.legacyorderacl.domain.LegacyOrderTranslation
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private fun row(
    number: String,
    status: String = "1",
) = LegacyOrderRow(
    number.padEnd(10),
    status,
    "山田商事".padEnd(40),
    "C0000101",
    "1200.00",
    1_791_450_000_000_000L,
    LegacyOrderTranslation.UNSET_LOCAL_MICROS,
)

private fun translated(
    number: String,
    status: String = "1",
): LegacyOrder =
    when (val r = LegacyOrderTranslation.translate(row(number, status))) {
        is Result.Ok -> r.value
        is Result.Err -> fail("変換できません: ${r.error}")
    }

/** 比べるたびに、次の段の行と出力を返す(比べ直しで状態が変わることを表す)。 */
private class Stages(
    val sources: List<List<LegacyOrderRow>>,
    val outputs: List<Map<String, LegacyOrder>>,
) : LegacySource,
    PublishedLegacyOrders {
    var reads = 0
    val scopes = mutableListOf<ReconcileScope>()
    var captureError: DomainError? = null

    override suspend fun read(scope: ReconcileScope): Result<SourceSnapshot, DomainError> {
        scopes += scope
        val rows = sources[minOf(reads, sources.lastIndex)]
        return ok(SourceSnapshot("0/${reads + 1}", rows.filter { scope.covers(it.orderNumber.trimEnd()) }))
    }

    override suspend fun awaitCaptured(position: String): Result<Unit, DomainError> = captureError?.let { err(it) } ?: ok(Unit)

    override suspend fun readCaughtUp(scope: ReconcileScope): Result<Map<String, LegacyOrder>, DomainError> =
        ok(outputs[minOf(reads++, outputs.lastIndex)].filterKeys { scope.covers(it) })

    private fun ReconcileScope.covers(key: String) = this is ReconcileScope.All || (this as ReconcileScope.Keys).orderNumbers.contains(key)
}

private suspend fun reconcile(
    stages: Stages,
    pauses: MutableList<Duration> = mutableListOf(),
): Result<ReconciliationReport, DomainError> =
    ReconcileLegacyOrdersService(stages, stages, { "h(" + it + ")" }, { pauses += it }, 30.seconds)()

class ReconcileLegacyOrdersSpec :
    FunSpec({
        test("すべて一致すれば、比べ直さずに一致を返す。全体のハッシュはキーの順で決まる") {
            val pauses = mutableListOf<Duration>()
            val stages = Stages(listOf(listOf(row("J1"), row("J2"))), listOf(mapOf("J2" to translated("J2"), "J1" to translated("J1"))))
            val report = (reconcile(stages, pauses) as Result.Ok).value
            report.consistent shouldBe true
            report.compared shouldBe 2
            pauses.shouldBeEmpty()
            report.position shouldBe "0/1"
            report.digest shouldBe "h(J1:h(" + "J1|C0000101|山田商事|ACCEPTED|1200|JPY|1791417600000000|-" + ")\nJ2:h(" +
                "J2|C0000101|山田商事|ACCEPTED|1200|JPY|1791417600000000|-" + "))"
        }

        test("比べ直しても食い違うキーを、種類(MISSING / STALE / EXTRA)ごとにずれとする。比べ直しは食い違ったキーだけ") {
            val sources = listOf(row("J1"), row("J2", "2"), row("J4"))
            val outputs = mapOf("J2" to translated("J2", "1"), "J3" to translated("J3"), "J4" to translated("J4"))
            val pauses = mutableListOf<Duration>()
            val stages = Stages(listOf(sources), listOf(outputs))

            val report = (reconcile(stages, pauses) as Result.Ok).value

            report.drift shouldBe mapOf("J1" to Mismatch.MISSING, "J2" to Mismatch.STALE, "J3" to Mismatch.EXTRA)
            pauses shouldBe listOf(30.seconds)
            stages.scopes shouldBe listOf(ReconcileScope.All, ReconcileScope.Keys(setOf("J1", "J2", "J3")))
            report.position shouldBe "0/2"
        }

        test("処理中だった変更(比べ直しで一致した)は、ずれにしない") {
            // 1 回目: 出力が 1 つ前の状態(引当済の変更をまだ処理していない)。2 回目: 追いついた
            val stages =
                Stages(
                    listOf(listOf(row("J1", "2"))),
                    listOf(mapOf("J1" to translated("J1", "1")), mapOf("J1" to translated("J1", "2"))),
                )
            (reconcile(stages) as Result.Ok).value.drift shouldBe emptyMap()
        }

        test("変換できない行(DLQ に入る)は既知の差として別に数え、ずれにしない。出力に古い状態が残っていても同じ") {
            val stages =
                Stages(
                    listOf(listOf(row("J1", "7"), row("J2"))),
                    listOf(mapOf("J1" to translated("J1"), "J2" to translated("J2"))),
                )
            val report = (reconcile(stages) as Result.Ok).value
            report.consistent shouldBe true
            report.unconvertible shouldBe setOf("J1")
            report.compared shouldBe 2
        }

        test("取り込み・処理の待ちが上限を超えたら、ずれではなく検査の失敗(Err)") {
            val stages = Stages(listOf(listOf(row("J1"))), listOf(emptyMap()))
            stages.captureError = UnavailableError("slot")
            reconcile(stages).shouldBeInstanceOf<Result.Err<DomainError>>().error shouldBe UnavailableError("slot")
        }

        test("レガシーにも出力にもないキーは比べない(削除が反映済み)") {
            val stages = Stages(listOf(listOf(row("J1"))), listOf(mapOf("J1" to translated("J1"))))
            (reconcile(stages) as Result.Ok).value.compared shouldBe 1
            LegacyOrderStatus.ACCEPTED.legacyCode shouldBe "1"
        }
    })
