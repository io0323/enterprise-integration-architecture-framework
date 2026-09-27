package io.eia.platform.observability.logging

/**
 * PEM の塊(`-----BEGIN <ラベル>-----` から `-----END <ラベル>-----` まで)を探して置き換える([Masking] の最初の規則。ADR-0018 §3)。
 *
 * 正規表現を使わず `indexOf` で走査するため、入力長に比例する時間で済み、再帰もしない(長い鍵でスタックがあふれない)。
 * ラベルは英大文字・数字・空白の 1〜64 文字。`-----END` が見つからなければ入力の末尾まで置き換える。
 * 間の本文の形(改行の種類・引用符・JSON のエスケープ・改行なし)は問わない。
 */
internal object PemBlocks {
    private const val BEGIN = "-----BEGIN "
    private const val END = "-----END "
    private const val DASHES = "-----"
    private const val MAX_LABEL = 64

    /** [text] の中の PEM の塊を、それぞれ [replacement] に置き換える。 */
    fun mask(
        text: String,
        replacement: String,
    ): String {
        var begin = text.indexOf(BEGIN)
        if (begin < 0) return text
        val out = StringBuilder(text.length)
        var copied = 0
        while (begin >= 0) {
            val headerEnd = labelEnd(text, begin + BEGIN.length)
            if (headerEnd < 0) {
                begin = text.indexOf(BEGIN, begin + 1)
                continue
            }
            val blockEnd = pemEnd(text, headerEnd)
            out.append(text, copied, begin).append(replacement)
            copied = blockEnd
            begin = if (blockEnd < text.length) text.indexOf(BEGIN, blockEnd) else -1
        }
        return out.append(text, copied, text.length).toString()
    }

    /** [from] 以降の最初の正しい `-----END <ラベル>-----` の直後の位置。なければ入力の末尾。 */
    private fun pemEnd(
        text: String,
        from: Int,
    ): Int {
        var end = text.indexOf(END, from)
        while (end >= 0) {
            val trailerEnd = labelEnd(text, end + END.length)
            if (trailerEnd >= 0) return trailerEnd
            end = text.indexOf(END, end + 1)
        }
        return text.length
    }

    /** [start] から始まる `<ラベル>-----` の直後の位置。ラベルが 1〜64 文字の英大文字・数字・空白でなければ -1。 */
    private fun labelEnd(
        text: String,
        start: Int,
    ): Int {
        var i = start
        while (i < text.length && i - start < MAX_LABEL && text[i].isPemLabelChar()) i++
        val valid = i > start && text.startsWith(DASHES, i)
        return if (valid) i + DASHES.length else -1
    }

    private fun Char.isPemLabelChar(): Boolean = this in 'A'..'Z' || this in '0'..'9' || this == ' '
}
