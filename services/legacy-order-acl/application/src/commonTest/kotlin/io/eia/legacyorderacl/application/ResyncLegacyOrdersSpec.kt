package io.eia.legacyorderacl.application

import io.eia.legacyorderacl.application.port.inbound.Mismatch
import io.eia.legacyorderacl.application.port.inbound.ReconciliationReport
import io.eia.legacyorderacl.application.port.inbound.ResyncResult
import io.eia.legacyorderacl.application.port.outbound.LegacySource
import io.eia.legacyorderacl.application.port.outbound.ReconcileScope
import io.eia.legacyorderacl.application.port.outbound.SourceSnapshot
import io.eia.legacyorderacl.application.usecase.ResyncLegacyOrdersService
import io.eia.legacyorderacl.domain.LegacyOrderRow
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

private fun report(drift: Map<String, Mismatch>) = ReconciliationReport("0/1", drift.size, drift, emptySet(), "d")

/** レガシーにある注文番号([present])だけを返す。 */
private class Present(
    private val present: Set<String>,
) : LegacySource {
    val reads = mutableListOf<ReconcileScope>()

    override suspend fun read(scope: ReconcileScope): Result<SourceSnapshot, DomainError> {
        reads += scope
        val keys = (scope as ReconcileScope.Keys).orderNumbers.filter { it in present }
        return ok(SourceSnapshot("0/2", keys.map { LegacyOrderRow(it.padEnd(10), "1", "x", "C", "1.00", 0, 0) }))
    }

    override suspend fun awaitCaptured(position: String): Result<Unit, DomainError> = ok(Unit)
}

class ResyncLegacyOrdersSpec :
    FunSpec({
        test("MISSING・STALE は Incremental Snapshot を指示し、EXTRA はレガシーにないことを確かめ直して tombstone を書く") {
            val requested = mutableListOf<Set<String>>()
            val tombstoned = mutableListOf<String>()
            val source = Present(setOf("J4"))
            val service =
                ResyncLegacyOrdersService(source, {
                    requested += it
                    ok(Unit)
                }, {
                    tombstoned += it
                    ok(Unit)
                })

            val result =
                service(report(mapOf("J1" to Mismatch.MISSING, "J2" to Mismatch.STALE, "J3" to Mismatch.EXTRA, "J4" to Mismatch.EXTRA)))

            result shouldBe ok(ResyncResult.Requested(setOf("J1", "J2"), setOf("J3"), setOf("J4")))
            requested shouldBe listOf(setOf("J1", "J2"))
            // 確かめ直したらレガシーにあった J4(新しく登録された)は消さない
            tombstoned shouldBe listOf("J3")
            source.reads shouldBe listOf(ReconcileScope.Keys(setOf("J3", "J4")))
        }

        test("ずれのキーが上限を超えたら、一部だけを取り直すこともせず、何もしない(人が判断する)") {
            val requested = mutableListOf<Set<String>>()
            val tombstoned = mutableListOf<String>()
            val source = Present(emptySet())
            val service =
                ResyncLegacyOrdersService(source, {
                    requested += it
                    ok(Unit)
                }, {
                    tombstoned += it
                    ok(Unit)
                }, limit = 3)
            val drift = (1..4).associate { "J$it" to if (it % 2 == 0) Mismatch.EXTRA else Mismatch.STALE }

            service(report(drift)) shouldBe ok(ResyncResult.OverLimit(4, 3))

            requested.shouldBeEmpty()
            tombstoned.shouldBeEmpty()
            source.reads.shouldBeEmpty()
            // 上限ちょうどなら取り直す
            service(report(drift - "J4")) shouldBe ok(ResyncResult.Requested(setOf("J1", "J3"), setOf("J2"), emptySet()))
        }

        test("ずれがなければ何もしない。EXTRA だけなら Snapshot は指示しない") {
            val requested = mutableListOf<Set<String>>()
            val service =
                ResyncLegacyOrdersService(Present(emptySet()), {
                    requested += it
                    ok(Unit)
                }, { ok(Unit) })
            service(report(emptyMap())) shouldBe ok(ResyncResult.NothingToDo)
            service(report(mapOf("J9" to Mismatch.EXTRA))) shouldBe ok(ResyncResult.Requested(emptySet(), setOf("J9"), emptySet()))
            requested.shouldBeEmpty()
        }

        test("指示・tombstone の失敗はそのまま返す(次の照合でもう一度ずれとして見つかる)") {
            val failure = UnavailableError("db")
            ResyncLegacyOrdersService(Present(emptySet()), { err(failure) }, { ok(Unit) })(report(mapOf("J1" to Mismatch.STALE))) shouldBe
                err(failure)
            ResyncLegacyOrdersService(Present(emptySet()), { ok(Unit) }, { err(failure) })(report(mapOf("J1" to Mismatch.EXTRA))) shouldBe
                err(failure)
        }
    })
