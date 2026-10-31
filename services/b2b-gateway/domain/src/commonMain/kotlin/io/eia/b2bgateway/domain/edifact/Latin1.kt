package io.eia.b2bgateway.domain.edifact

/**
 * バイト列と文字列の 1 対 1 の変換(ISO 8859-1)。UNOA・UNOB・UNOC はどれも 1 バイトが 1 文字なので、文字セットを読む前に全体を
 * この形で文字列にし、区切り文字(ASCII)で区切ってから、文字セットの集合を確かめる(ADR-0041 §3)。
 */
public object Latin1 {
    private const val BYTE_MASK = 0xFF

    public fun decode(bytes: ByteArray): String = CharArray(bytes.size) { (bytes[it].toInt() and BYTE_MASK).toChar() }.concatToString()

    /** 文字列をバイト列にする。0xFF を超える文字は、呼び出し側の誤り(受け取った文字セットの値だけを書く。[CharacterSet])。 */
    public fun encode(text: String): ByteArray =
        ByteArray(text.length) { index ->
            val code = text[index].code
            require(code <= BYTE_MASK) { "ISO 8859-1 で表せない文字があります(位置 $index)" }
            code.toByte()
        }
}
