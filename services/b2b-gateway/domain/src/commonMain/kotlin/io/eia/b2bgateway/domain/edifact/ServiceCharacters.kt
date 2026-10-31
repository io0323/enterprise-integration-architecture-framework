package io.eia.b2bgateway.domain.edifact

/**
 * 区切り文字(UNA の 6 文字。ADR-0041 §2): 構成要素の区切り・データ要素の区切り・小数点・解放文字・予約(版 3 では空白)・セグメントの終端。
 */
public data class ServiceCharacters(
    public val component: Char,
    public val element: Char,
    public val decimal: Char,
    public val release: Char,
    public val reserved: Char,
    public val terminator: Char,
) {
    /** UNA のセグメント(`UNA` と 6 文字)。 */
    public val una: String get() = "$UNA$component$element$decimal$release$reserved$terminator"

    /** 値の中で解放文字を前に置く文字(区切り・解放文字・終端)。 */
    public val escaped: Set<Char> get() = setOf(component, element, release, terminator)

    /** 区切り文字として正しいか(予約の位置は空白、区切り・解放・終端・小数点は互いに違い、英数字と空白を使わない)。 */
    public val isValid: Boolean
        get() {
            val delimiters = listOf(component, element, decimal, release, terminator)
            return reserved == ' ' && delimiters.distinct().size == delimiters.size && delimiters.none { it.isLetterOrDigit() || it == ' ' }
        }

    public companion object {
        public const val UNA: String = "UNA"
        public const val UNA_LENGTH: Int = 9

        /**
         * UNA がない交換を読むときの区切り文字。UNA は必須なので、これは応答の宛先(UNB の送り手と管理番号)を読み取るためだけに使う
         * (小数点は使わないので、例の `.` にしている。ADR-0041 §2)。
         */
        public val FALLBACK: ServiceCharacters = ServiceCharacters(':', '+', '.', '?', ' ', '\'')

        /** 先頭の UNA を読む。UNA で始まらないか、9 文字に満たなければ null。 */
        @Suppress("MagicNumber") // UNA の 6 文字の位置(ISO 9735)
        public fun fromUna(text: String): ServiceCharacters? =
            if (text.length >= UNA_LENGTH && text.startsWith(UNA)) {
                ServiceCharacters(text[3], text[4], text[5], text[6], text[7], text[8])
            } else {
                null
            }
    }
}
