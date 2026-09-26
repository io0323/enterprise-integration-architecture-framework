package io.eia.tools.contract

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.dataformat.yaml.YAMLMapper
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.io.path.isRegularFile

/**
 * 互換性検査(CC-COMPAT-*)の例外 1 件(ADR-0013 §6)。
 * [rule] と [file] が一致し、内容に [contains] を含む違反を許可する。[expires] の翌日から CC-WAIVER-002 で失敗する。
 */
data class Waiver(
    val rule: String,
    val file: String,
    val contains: String,
    val reason: String,
    val issue: String,
    val expires: LocalDate,
)

/** 例外で許可した違反と、その根拠の例外。 */
data class WaivedViolation(
    val violation: Violation,
    val waiver: Waiver,
)

/** 例外を適用した結果。[unused] はどの違反にも当たらなかった例外(削除候補。違反にはしない)。 */
class WaiverResult(
    val violations: List<Violation>,
    val waived: List<WaivedViolation>,
    val unused: List<Waiver>,
)

/** contracts/compat-waivers.yaml の読み込みと適用。 */
object Waivers {
    const val FILE = "contracts/compat-waivers.yaml"

    private const val WAIVABLE_PREFIX = "CC-COMPAT-"
    private val REQUIRED = listOf("rule", "file", "contains", "reason", "issue", "expires")
    private val yaml = YAMLMapper.builder().build()

    /** 例外の一覧と、形式の違反(CC-WAIVER-001)を返す。ファイルがなければどちらも空。 */
    fun load(root: Path): Pair<List<Waiver>, List<Violation>> {
        val file = root.resolve(FILE)
        if (!file.isRegularFile()) return emptyList<Waiver>() to emptyList()
        return try {
            val entries = yaml.readTree(file.toFile())?.get("waivers")
            if (entries == null || !entries.isArray) {
                emptyList<Waiver>() to listOf(invalid("トップレベルに waivers の配列がありません"))
            } else {
                val parsed = entries.values().mapIndexed { index, node -> parse("waivers[$index]", node) }
                parsed.mapNotNull { it.first } to parsed.mapNotNull { it.second }
            }
        } catch (e: JacksonException) {
            emptyList<Waiver>() to listOf(Violation(FILE, Rule.STRUCT_PARSE, e.originalMessage ?: e.message.orEmpty()))
        }
    }

    /** [violations] に例外を適用する。期限切れの例外は違反を許可せず、CC-WAIVER-002 を加える。 */
    fun apply(
        violations: List<Violation>,
        waivers: List<Waiver>,
        today: LocalDate,
    ): WaiverResult {
        val (active, expired) = waivers.partition { !today.isAfter(it.expires) }
        val waived = mutableListOf<WaivedViolation>()
        val remaining =
            violations.filter { violation ->
                val waiver = active.firstOrNull { it.matches(violation) } ?: return@filter true
                waived += WaivedViolation(violation, waiver)
                false
            }
        val expiredViolations =
            expired.map { waiver ->
                Violation(FILE, Rule.WAIVER_EXPIRED, "${waiver.rule} ${waiver.file}(${waiver.issue})の例外は ${waiver.expires} で期限切れです")
            }
        val used = waived.map { it.waiver }.toSet()
        return WaiverResult(remaining + expiredViolations, waived, active.filterNot { it in used })
    }

    private fun Waiver.matches(violation: Violation): Boolean =
        violation.rule.id == rule && violation.file == file && contains in violation.message

    /** 例外 1 件を読む。読めなければ形式の違反を返す。 */
    private fun parse(
        label: String,
        node: JsonNode,
    ): Pair<Waiver?, Violation?> {
        val missing = REQUIRED.filter { node.text(it).isNullOrBlank() }
        val rule = node.text("rule").orEmpty()
        val expires = node.text("expires")?.let { runCatchingDate(it) }
        val error =
            when {
                missing.isNotEmpty() -> {
                    "$label: 必須項目がありません: ${missing.joinToString()}"
                }

                !rule.startsWith(WAIVABLE_PREFIX) || Rule.entries.none { it.id == rule } -> {
                    "$label: rule '$rule' は例外にできません(対象は $WAIVABLE_PREFIX* のみ)"
                }

                expires == null -> {
                    "$label: expires は YYYY-MM-DD で書いてください: ${node.text("expires")}"
                }

                else -> {
                    null
                }
            }
        return if (error != null || expires == null) {
            null to invalid(error.orEmpty())
        } else {
            Waiver(
                rule = rule,
                file = node.text("file").orEmpty(),
                contains = node.text("contains").orEmpty(),
                reason = node.text("reason").orEmpty(),
                issue = node.text("issue").orEmpty(),
                expires = expires,
            ) to null
        }
    }

    private fun runCatchingDate(text: String): LocalDate? =
        try {
            LocalDate.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }

    private fun invalid(message: String) = Violation(FILE, Rule.WAIVER_INVALID, message)
}
