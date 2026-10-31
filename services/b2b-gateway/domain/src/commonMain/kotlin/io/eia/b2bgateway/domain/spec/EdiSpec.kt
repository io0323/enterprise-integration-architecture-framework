package io.eia.b2bgateway.domain.spec

import io.eia.b2bgateway.domain.edifact.CharacterSet

/**
 * EDI のサブセットの仕様(contracts/files/edi/{name}.v{n}.yaml。ADR-0041 §7)。adapters が契約の YAML から作り、検査と応答の組み立てに使う。
 * 形と参照の食い違いは contract-check(CC-EDI-001・002)が検査するので、ここでは検査しない。
 */
public data class EdiSpec(
    public val name: String,
    public val version: Int,
    public val direction: Direction,
    public val message: MessageIdentifier,
    public val syntax: SyntaxRules,
    public val segments: Map<String, SegmentDefinition>,
    public val structure: List<StructureNode>,
    public val businessRules: List<RuleText> = emptyList(),
    public val errorTexts: List<RuleText> = emptyList(),
) {
    /** UNB・UNH・UNT・UNZ の定義(どの仕様にもある。CC-EDI-002)。 */
    public fun service(tag: String): SegmentDefinition = checkNotNull(segments[tag]) { "仕様 $name に $tag の定義がありません" }
}

public enum class Direction { INBOUND, OUTBOUND }

/** UNH の S009(0065・0052・0054・0051)。 */
public data class MessageIdentifier(
    public val type: String,
    public val version: String,
    public val release: String,
    public val agency: String,
) {
    public val values: List<String> get() = listOf(type, version, release, agency)
}

public data class SyntaxRules(
    public val versions: Set<Int>,
    public val characterSets: Set<CharacterSet>,
    public val maxMessagesPerInterchange: Int,
)

public data class SegmentDefinition(
    public val tag: String,
    public val elements: List<ElementDefinition>,
)

/** 状態。M = 必須、C = 任意、X = このサブセットでは使わない(空でなければ誤り)。 */
public enum class Status { MANDATORY, CONDITIONAL, NOT_USED }

public sealed interface ElementDefinition {
    public val id: String
    public val status: Status

    public data class Simple(
        override val id: String,
        override val status: Status,
        public val format: DataFormat,
        public val codes: Set<String> = emptySet(),
        public val semantic: Semantic? = null,
    ) : ElementDefinition

    public data class Composite(
        override val id: String,
        override val status: Status,
        public val components: List<Simple>,
    ) : ElementDefinition
}

/** 形式に加えた意味の検査。DATE = CCYYMMDD の実在する日付、TIME = HHMM。 */
public enum class Semantic { DATE, TIME }

public enum class CharacterType { ALPHABETIC, NUMERIC, ALPHANUMERIC }

/** ISO 9735 の表現(例 an..35・n6)。[upTo] が true なら [length] は最大の長さ、false なら固定の長さ。 */
public data class DataFormat(
    public val type: CharacterType,
    public val length: Int,
    public val upTo: Boolean,
) {
    public companion object {
        private val FORMAT = Regex("^(a|n|an)(\\.\\.)?([1-9][0-9]*)$")

        /** `an..35` などを読む。形が違えば null。 */
        public fun parse(text: String): DataFormat? {
            val (type, range, length) = FORMAT.matchEntire(text)?.destructured ?: return null
            val characterType =
                when (type) {
                    "a" -> CharacterType.ALPHABETIC
                    "n" -> CharacterType.NUMERIC
                    else -> CharacterType.ALPHANUMERIC
                }
            return DataFormat(characterType, length.toInt(), range.isNotEmpty())
        }
    }
}

/** 修飾子(セグメントの最初のデータ要素の最初の値)ごとの回数。 */
public data class Qualifier(
    public val value: String,
    public val mandatory: Boolean,
    public val max: Int,
)

/** UNH と UNT の間の並び。 */
public sealed interface StructureNode {
    public val mandatory: Boolean
    public val max: Int
    public val qualifiers: List<Qualifier>

    public data class SegmentNode(
        public val tag: String,
        override val mandatory: Boolean,
        override val max: Int,
        override val qualifiers: List<Qualifier> = emptyList(),
    ) : StructureNode

    /** セグメントグループ。最初の要素(トリガーセグメント)は必須のセグメント(CC-EDI-002)。 */
    public data class GroupNode(
        public val name: String,
        override val mandatory: Boolean,
        override val max: Int,
        public val content: List<StructureNode>,
        override val qualifiers: List<Qualifier> = emptyList(),
    ) : StructureNode {
        public val trigger: String get() = (content.first() as SegmentNode).tag
    }
}

/** 業務の規則のコード(APERAK の ERC の 9321)と、取引先に返す固定の文言(FTX の 4440)。 */
public data class RuleText(
    public val code: String,
    public val text: String,
)
