package io.eia.tools.contract

import io.eia.tools.contract.canonical.CanonicalAvroConformance
import io.eia.tools.contract.canonical.CanonicalBinding
import io.eia.tools.contract.canonical.CanonicalBindings
import io.eia.tools.contract.rules.AsyncApiRules
import io.eia.tools.contract.rules.AvroRules
import io.eia.tools.contract.rules.CatalogRules
import io.eia.tools.contract.rules.FileRules
import io.eia.tools.contract.rules.OpenApiCompatibility
import io.eia.tools.contract.rules.OpenApiRules
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.isDirectory

/**
 * 検査結果。[baselineUsed] が false なら互換性検査(CC-COMPAT-*)は比較元なしで省略している。
 * [waived] は例外(contracts/compat-waivers.yaml)で許可した違反、[unusedWaivers] はどの違反にも当たらなかった例外。
 */
class CheckResult(
    val violations: List<Violation>,
    val baselineUsed: Boolean,
    val waived: List<WaivedViolation> = emptyList(),
    val unusedWaivers: List<Waiver> = emptyList(),
)

/**
 * contracts/ の全検査を実行する。
 *
 * @param root 検査するリポジトリのルート(contracts/ を含むディレクトリ)
 * @param baselineRoot 互換性検査の比較元(main の contracts/ を含むディレクトリ)。null か存在しなければ互換性検査を省略する
 * @param oasdiff oasdiff の実行ファイル
 * @param today 例外の期限の判定に使う日付(UTC)
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
                    AvroRules.checkFullCompatibility(contracts, it) + OpenApiCompatibility(oasdiff).check(contracts, it)
                }.orEmpty()
        val (waivers, waiverViolations) = Waivers.load(root)
        val waived = Waivers.apply(compatibility, waivers, today)
        val violations =
            contracts.loadViolations +
                waiverViolations +
                openApi.violations +
                asyncApi.violations +
                AvroRules.checkNaming(contracts) +
                CatalogRules(contracts, asyncApi.addressesByFile, openApi.domainsByFile).check() +
                FileRules.check(contracts) +
                checkCanonical(contracts) +
                waived.violations
        return CheckResult(
            violations = violations.distinct().sortedWith(compareBy({ it.file }, { it.rule.id })),
            baselineUsed = baseline != null,
            waived = waived.waived,
            unusedWaivers = waived.unused,
        )
    }

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
