package io.eia.tools.contract

import io.eia.tools.contract.canonical.CanonicalAvroConformance
import io.eia.tools.contract.canonical.CanonicalBinding
import io.eia.tools.contract.canonical.CanonicalBindings
import io.eia.tools.contract.rules.AsyncApiRules
import io.eia.tools.contract.rules.AvroRules
import io.eia.tools.contract.rules.CatalogRules
import io.eia.tools.contract.rules.ContractUsage
import io.eia.tools.contract.rules.DeviceRules
import io.eia.tools.contract.rules.FileCompatibility
import io.eia.tools.contract.rules.FileRules
import io.eia.tools.contract.rules.FlowRules
import io.eia.tools.contract.rules.LifecycleRules
import io.eia.tools.contract.rules.OpenApiCompatibility
import io.eia.tools.contract.rules.OpenApiRules
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.isDirectory

/**
 * 検査結果。[baselineUsed] が false なら、比較元が要る検査(互換性 CC-COMPAT-*・ライフサイクル CC-LIFECYCLE-001〜004)は省略している。
 * [waived] は例外(contracts/compat-waivers.yaml)で許可した違反、[unusedWaivers] はどの違反にも当たらなかった例外。
 * [warnings] は知らせるだけの指摘(移行の期限を過ぎた deprecated の連携など。ジョブは失敗させない)。
 * [designAllowed] は、比較元で proposed・design の連携だけが使う契約の互換性の違反(設計中なので許可。ADR-0013 §7)。
 */
class CheckResult(
    val violations: List<Violation>,
    val baselineUsed: Boolean,
    val waived: List<WaivedViolation> = emptyList(),
    val unusedWaivers: List<Waiver> = emptyList(),
    val warnings: List<Violation> = emptyList(),
    val designAllowed: List<Violation> = emptyList(),
)

/**
 * contracts/ の全検査を実行する。
 *
 * @param root 検査するリポジトリのルート(contracts/ を含むディレクトリ)
 * @param baselineRoot 互換性検査の比較元(main の contracts/ を含むディレクトリ)。null か存在しなければ互換性検査を省略する
 * @param oasdiff oasdiff の実行ファイル
 * @param today 例外の期限・連携の移行の期限の判定に使う日付(UTC)
 */
class ContractCheck(
    private val root: Path,
    private val baselineRoot: Path?,
    private val oasdiff: Path?,
    private val bindings: List<CanonicalBinding> = CanonicalBindings.ALL,
    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC),
) {
    fun run(): CheckResult {
        val contracts = Contracts.load(root)
        val openApi = OpenApiRules.check(contracts)
        val asyncApi = AsyncApiRules.check(contracts)
        val baseline = baselineRoot?.takeIf { it.resolve(Contracts.CONTRACTS_DIR).isDirectory() }?.let(Contracts::load)
        val compatibility =
            baseline
                ?.let {
                    AvroRules.checkFullCompatibility(contracts, it) +
                        OpenApiCompatibility(oasdiff).check(contracts, it) +
                        FileCompatibility.check(contracts, it) +
                        DeviceRules.checkUnchanged(contracts, it)
                }.orEmpty()
        val lifecycle = LifecycleRules(contracts, baseline, today)
        // 比較元で設計中(proposed・design)の連携だけが使う契約は、破壊的変更を許す(消費者がまだいない。ADR-0013 §7)
        // 外部の契約(contracts/external)の破壊的変更は、こちらでは止められないので警告にする(ADR-0035 §8。CC-EXTERNAL-001)
        val (externalChanges, ours) = compatibility.partition { it.file.startsWith(Contracts.EXTERNAL_DIR) && it.rule != Rule.TOOL_FAILURE }
        val designUsage = baseline?.let(ContractUsage::of).orEmpty()
        val (designAllowed, enforced) = ours.partition { it.rule.id.startsWith(COMPAT_PREFIX) && inDesignOnly(designUsage[it.file]) }
        val (waivers, waiverViolations) = Waivers.load(root)
        val waived = Waivers.apply(enforced, waivers, today)
        val violations =
            contracts.loadViolations +
                waiverViolations +
                Waivers.checkActive(waivers, ContractUsage.of(contracts)) +
                openApi.violations +
                asyncApi.violations +
                AvroRules.checkNaming(contracts) +
                OpenApiRules.checkExternal(contracts) +
                FlowRules(contracts).check() +
                CatalogRules(contracts, asyncApi.addressesByFile, openApi.domainsByFile).check() +
                FileRules.check(contracts) +
                DeviceRules.check(contracts) +
                checkCanonical(contracts) +
                lifecycle.check() +
                waived.violations
        return CheckResult(
            violations = violations.distinct().sortedWith(compareBy({ it.file }, { it.rule.id })),
            baselineUsed = baseline != null,
            waived = waived.waived,
            unusedWaivers = waived.unused,
            warnings =
                (lifecycle.warnings() + externalChanges.map { Violation(it.file, Rule.EXTERNAL_BREAKING, it.message) })
                    .sortedWith(compareBy({ it.file }, { it.rule.id })),
            designAllowed = designAllowed.sortedWith(compareBy({ it.file }, { it.rule.id })),
        )
    }

    /** 契約を使う連携があり、すべて proposed・design なら true(どの連携も使っていない契約は、許可しない)。 */
    private fun inDesignOnly(users: List<CatalogDocument>?): Boolean =
        !users.isNullOrEmpty() && users.all { it.entry.lifecycle in DESIGN_STATES }

    private fun checkCanonical(contracts: Contracts): List<Violation> {
        val namedTypes =
            contracts.avros
                .flatMap { document -> AvroRules.namedTypes(document.schema).map { it.fullName to (document to it) } }
                .toMap()
        return bindings.flatMap { binding ->
            val (document, schema) =
                namedTypes[binding.avroFullName]
                    ?: return@flatMap listOf(
                        Violation(
                            "${Contracts.CONTRACTS_DIR}/avro",
                            Rule.CANONICAL_AVRO_MISSING,
                            "${binding.descriptor.serialName} に対応する Avro の型 '${binding.avroFullName}' がありません",
                        ),
                    )
            CanonicalAvroConformance.compare(binding.descriptor, schema).map {
                Violation(document.path, Rule.CANONICAL_AVRO, "${binding.descriptor.serialName} ⇔ ${binding.avroFullName}: $it")
            }
        }
    }
}

private val DESIGN_STATES = setOf("proposed", "design")
private const val COMPAT_PREFIX = "CC-COMPAT-"
