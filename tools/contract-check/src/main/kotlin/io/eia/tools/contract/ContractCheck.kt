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
import kotlin.io.path.isDirectory

/** 検査結果。[baselineUsed] が false なら互換性検査(CC-COMPAT-*)は比較元なしで省略している。 */
class CheckResult(
    val violations: List<Violation>,
    val baselineUsed: Boolean,
)

/**
 * contracts/ の全検査を実行する。
 *
 * @param root 検査するリポジトリのルート(contracts/ を含むディレクトリ)
 * @param baselineRoot 互換性検査の比較元(main の contracts/ を含むディレクトリ)。null か存在しなければ互換性検査を省略する
 * @param oasdiff oasdiff の実行ファイル
 */
class ContractCheck(
    private val root: Path,
    private val baselineRoot: Path?,
    private val oasdiff: Path?,
    private val bindings: List<CanonicalBinding> = CanonicalBindings.ALL,
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
        val violations =
            contracts.loadViolations +
                openApi.violations +
                asyncApi.violations +
                AvroRules.checkNaming(contracts) +
                CatalogRules(contracts, asyncApi.addressesByFile, openApi.domainsByFile).check() +
                FileRules.check(contracts) +
                checkCanonical(contracts) +
                compatibility
        return CheckResult(violations.distinct().sortedWith(compareBy({ it.file }, { it.rule.id })), baseline != null)
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
