package io.eia.b2bgateway.domain.edifact

/**
 * セグメント 1 つ。[elements] はタグの次のデータ要素から並べ、データ要素ごとに構成要素の値の並び(単純データ要素は 1 つ)を持つ。
 * 値は解放文字を外した後のもの。[index] は交換の中の順番(0 から。UNA は数えない)。
 */
public data class Segment(
    public val tag: String,
    public val elements: List<List<String>>,
    public val index: Int = 0,
) {
    /** [position] 番目(1 から)のデータ要素の [component] 番目(1 から)の値。なければ空文字列。 */
    public fun value(
        position: Int,
        component: Int = 1,
    ): String =
        elements
            .getOrNull(position - 1)
            ?.getOrNull(component - 1)
            .orEmpty()

    public companion object {
        /** 単純データ要素だけのセグメントを作る(書き出し用)。 */
        public fun of(
            tag: String,
            vararg elements: List<String>,
        ): Segment = Segment(tag, elements.toList())
    }
}

/**
 * 字句の解析で見つけた誤り。位置はセグメントの [segmentIndex](交換の中の順番)と、データ要素・構成要素の位置(1 から。null は不明)。
 * どのセグメントにも属さない誤り(UNA)は [segmentIndex] が null。
 */
public data class LexIssue(
    public val segmentIndex: Int?,
    public val element: Int?,
    public val component: Int?,
    public val kind: LexIssueKind,
)

public enum class LexIssueKind {
    /** UNA がない(必須。ADR-0041 §2) */
    UNA_MISSING,

    /** UNA の区切り文字が正しくない(予約の位置が空白でない・重複・英数字) */
    UNA_INVALID,

    /** 解放文字の後に、区切り・解放文字・終端でない文字がある */
    RELEASE_NOT_BEFORE_SERVICE_CHARACTER,

    /** セグメントのタグが英大文字 3 文字でない */
    INVALID_TAG,
}
