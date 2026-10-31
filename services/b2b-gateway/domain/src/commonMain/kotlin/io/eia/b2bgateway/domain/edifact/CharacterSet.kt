package io.eia.b2bgateway.domain.edifact

/**
 * 構文の版 3 の文字セット(UNB の S001 の 0001。ADR-0041 §3)。どれも 1 バイトが 1 文字なので、交換は ISO 8859-1 として読み([Latin1])、
 * 文字ごとに集合に入るかを確かめる。
 *
 * - UNOA(UN/ECE level A): ISO 646 の基本の表から、英小文字と、国や用途で割り当てが変わる位置(`# $ @ [ \ ] ^ ` { | } ~`)を除いた集合。
 * - UNOB(UN/ECE level B): ISO 646 の基本の表から、国や用途で割り当てが変わる位置を除いた集合(UNOA に英小文字を足したもの)。
 * - UNOC(UN/ECE level C): ISO/IEC 8859-1 の図形文字(0x20〜0x7E と 0xA0〜0xFF)。
 *
 * 制御文字(CR・LF を含む)はどの集合にも入らない。セグメントの終端の直後の CR・LF は、字句の解析([EdifactLexer])が読み飛ばす。
 */
public enum class CharacterSet(
    public val identifier: String,
) {
    UNOA("UNOA"),
    UNOB("UNOB"),
    UNOC("UNOC"),
    ;

    public fun allows(c: Char): Boolean =
        when (this) {
            UNOA -> c in 'A'..'Z' || isInvariantNonLetter(c)
            UNOB -> c in 'A'..'Z' || c in 'a'..'z' || isInvariantNonLetter(c)
            UNOC -> c.code in PRINTABLE_ASCII || c.code in LATIN1_GRAPHIC
        }

    public companion object {
        // ISO 646 の、国や用途で割り当てが変わらない位置の英字以外の文字(数字・空白・記号)
        private const val INVARIANT_SYMBOLS = " !\"%&'()*+,-./:;<=>?_"
        private val PRINTABLE_ASCII = 0x20..0x7E
        private val LATIN1_GRAPHIC = 0xA0..0xFF

        private fun isInvariantNonLetter(c: Char): Boolean = c in '0'..'9' || c in INVARIANT_SYMBOLS

        /** UNB の 0001 の値から。知らない値(UNOD〜・UNOY など)は null。 */
        public fun of(identifier: String): CharacterSet? = entries.firstOrNull { it.identifier == identifier }
    }
}
