package io.eia.shared.sdk.http.token

/**
 * application/x-www-form-urlencoded の値のエンコード(JVM の `URLEncoder.encode(value, UTF_8)` と同じ結果)。
 * 英数字と `.`・`-`・`*`・`_` はそのまま、空白は `+`、ほかは UTF-8 のバイトごとに `%XX`(大文字の 16 進)にする。
 * `client_secret_basic` の ID と Secret に使う(RFC 6749 §2.3.1)。
 */
internal object FormEncoding {
    private const val HEX = "0123456789ABCDEF"
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0F
    private const val BYTE_MASK = 0xFF

    fun encode(value: String): String =
        buildString {
            value.encodeToByteArray().forEach { byte ->
                val c = (byte.toInt() and BYTE_MASK).toChar()
                when {
                    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in ".-*_" -> {
                        append(c)
                    }

                    c == ' ' -> {
                        append('+')
                    }

                    else -> {
                        val unsigned = byte.toInt() and BYTE_MASK
                        append('%')
                        append(HEX[unsigned shr NIBBLE_BITS])
                        append(HEX[unsigned and NIBBLE_MASK])
                    }
                }
            }
        }
}
