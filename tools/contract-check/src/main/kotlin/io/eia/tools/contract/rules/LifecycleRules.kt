package io.eia.tools.contract.rules

import io.eia.tools.contract.CatalogDocument
import io.eia.tools.contract.Contracts
import io.eia.tools.contract.Rule
import io.eia.tools.contract.Violation
import io.eia.tools.contract.text
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 連携のライフサイクルの検査(Framework 16.1。ADR-0034)。比較元(main)のカタログと比べる。
 *
 * - [check]: 状態の遷移(`CC-LIFECYCLE-001`)・エントリの削除(`002`)・契約のファイルの削除(`003`)・期限の前の retired(`004`)。
 *   比較元がなければ省略する(互換性検査と同じ)。
 * - [warnings]: 移行の期限を過ぎた deprecated の連携(`005`。ゾンビ連携)。比較元は要らない。ジョブは失敗させない。
 *
 * 状態ごとの項目(deprecated の `deprecation`・retired の `retirement`)の有無は、カタログスキーマ(`CC-CATALOG-001`)で検査する。
 */
class LifecycleRules(
    private val contracts: Contracts,
    private val baseline: Contracts?,
    private val today: LocalDate,
) {
    fun check(): List<Violation> {
        val base = baseline ?: return emptyList()
        val baseById = base.catalogs.byId()
        val currentById = contracts.catalogs.byId()
        return contracts.catalogs.flatMap { checkTransition(it, baseById[it.entry.id]) } +
            baseById.filterKeys { it !in currentById }.values.flatMap(::checkRemoved) +
            checkRemovedContracts(base)
    }

    fun warnings(): List<Violation> =
        contracts.catalogs.mapNotNull { catalog ->
            val sunset = catalog.sunset()
            if (catalog.entry.lifecycle == DEPRECATED && sunset != null && sunset < today) {
                Violation(
                    catalog.path,
                    Rule.LIFECYCLE_PAST_SUNSET,
                    "${catalog.entry.id} は移行の期限 $sunset を過ぎても deprecated のままです(ゾンビ連携)",
                )
            } else {
                null
            }
        }

    /** 状態の遷移。新しいエントリは proposed / design から(既存の連携の後からの登録だけ active から)。 */
    @Suppress("ReturnCount") // 新しいエントリ・同じ状態・許す遷移のそれぞれで返す
    private fun checkTransition(
        catalog: CatalogDocument,
        base: CatalogDocument?,
    ): List<Violation> {
        val to = catalog.entry.lifecycle ?: return emptyList()
        if (base == null) return checkNewEntry(catalog, to)
        val from = base.entry.lifecycle ?: return emptyList()
        val fromIndex = ORDER.indexOf(from)
        val toIndex = ORDER.indexOf(to)
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return emptyList()
        return when {
            toIndex == fromIndex + 1 -> {
                if (to == RETIRED) checkSunsetReached(catalog, base) else emptyList()
            }

            from == DEPRECATED && to == ACTIVE -> {
                checkReactivation(catalog)
            }

            else -> {
                val kind = if (toIndex < fromIndex) "戻せません" else "1 つの PR で 2 段階以上は進められません"
                listOf(Violation(catalog.path, Rule.LIFECYCLE_TRANSITION, "${catalog.entry.id} の状態は $from → $to に$kind"))
            }
        }
    }

    private fun checkNewEntry(
        catalog: CatalogDocument,
        lifecycle: String,
    ): List<Violation> {
        val onboarding = catalog.tree.get("onboarding")
        val existing = onboarding?.text("kind") == EXISTING && !onboarding.text("reason").isNullOrBlank()
        return when {
            lifecycle in setOf(PROPOSED, DESIGN) -> {
                emptyList()
            }

            lifecycle == ACTIVE && existing -> {
                emptyList()
            }

            lifecycle == ACTIVE -> {
                listOf(
                    Violation(
                        catalog.path,
                        Rule.LIFECYCLE_TRANSITION,
                        "新しい連携 ${catalog.entry.id} は proposed か design で登録します" +
                            "(既存の連携の後からの登録なら onboarding に kind: existing と reason を書きます)",
                    ),
                )
            }

            else -> {
                listOf(
                    Violation(
                        catalog.path,
                        Rule.LIFECYCLE_TRANSITION,
                        "新しい連携 ${catalog.entry.id} を $lifecycle で登録できません(proposed か design で登録します)",
                    ),
                )
            }
        }
    }

    /** deprecated → active(廃止の取り消し)は、reactivation に reason と approver を書いたときだけ。 */
    private fun checkReactivation(catalog: CatalogDocument): List<Violation> {
        val reactivation = catalog.tree.get("reactivation")
        val missing = listOf("reason", "approver").filter { reactivation?.text(it).isNullOrBlank() }
        return if (missing.isEmpty()) {
            emptyList()
        } else {
            listOf(
                Violation(
                    catalog.path,
                    Rule.LIFECYCLE_TRANSITION,
                    "${catalog.entry.id} を deprecated から active に戻すには、reactivation の ${missing.joinToString()} が要ります",
                ),
            )
        }
    }

    /** deprecated → retired は、比較元の移行の期限の後だけ。 */
    private fun checkSunsetReached(
        catalog: CatalogDocument,
        base: CatalogDocument,
    ): List<Violation> {
        val sunset = base.sunset() ?: return emptyList()
        return if (today < sunset) {
            listOf(
                Violation(
                    catalog.path,
                    Rule.LIFECYCLE_RETIRED_BEFORE_SUNSET,
                    "${catalog.entry.id} の移行の期限は $sunset です。期限の前に retired にはできません",
                ),
            )
        } else {
            emptyList()
        }
    }

    private fun checkRemoved(base: CatalogDocument): List<Violation> =
        if (base.entry.lifecycle == RETIRED) {
            emptyList()
        } else {
            listOf(
                Violation(
                    base.path,
                    Rule.LIFECYCLE_ENTRY_REMOVED,
                    "${base.entry.id}(${base.entry.lifecycle})を消しました。retired にしてから消します",
                ),
            )
        }

    /** 比較元で retired 以外の連携が使う契約のファイル(と、AsyncAPI が参照する Avro)が、消えていないこと。 */
    private fun checkRemovedContracts(base: Contracts): List<Violation> {
        val asyncApis = base.asyncApis.associateBy { it.path }
        val used =
            base.catalogs
                .filter { it.entry.lifecycle != RETIRED }
                .flatMap { catalog ->
                    val contract = catalog.entry.contract ?: return@flatMap emptyList()
                    val avros = asyncApis[contract]?.let { AsyncApiRules.avroReferences(base, it) }.orEmpty()
                    (listOf(contract) + avros).map { it to catalog.entry.id }
                }.groupBy({ it.first }, { it.second })
        return used
            .filterKeys {
                !contracts.root
                    .resolve(it)
                    .toFile()
                    .isFile
            }.map { (path, ids) ->
                Violation(
                    path,
                    Rule.LIFECYCLE_CONTRACT_REMOVED,
                    "${ids.filterNotNull().distinct().sorted().joinToString()} が使う契約のファイルを消しました",
                )
            }
    }

    private fun List<CatalogDocument>.byId(): Map<String, CatalogDocument> =
        mapNotNull { catalog -> catalog.entry.id?.let { it to catalog } }.toMap()

    private fun CatalogDocument.sunset(): LocalDate? =
        tree.get("deprecation")?.text("sunset")?.let {
            try {
                LocalDate.parse(it)
            } catch (_: DateTimeParseException) {
                null
            }
        }

    private companion object {
        const val PROPOSED = "proposed"
        const val DESIGN = "design"
        const val ACTIVE = "active"
        const val DEPRECATED = "deprecated"
        const val RETIRED = "retired"
        const val EXISTING = "existing"
        val ORDER = listOf(PROPOSED, DESIGN, ACTIVE, DEPRECATED, RETIRED)
    }
}
