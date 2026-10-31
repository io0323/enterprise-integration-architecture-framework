package io.eia.b2bgateway.domain.edifact

/**
 * 字句の解析の結果。[serviceCharacters] は UNA の値(UNA がない・正しくないときは [ServiceCharacters.FALLBACK])。
 * [terminated] が false なら、最後のセグメントが終端で終わっていない(書いている途中か、壊れている)。
 */
public data class Lexed(
    public val serviceCharacters: ServiceCharacters,
    public val una: String?,
    public val segments: List<Segment>,
    public val issues: List<LexIssue>,
    public val terminated: Boolean,
)

/**
 * 交換の文字列をセグメントに分ける(ADR-0041 §2)。
 * - 先頭は UNA(区切り文字の指定)。ないときも、応答の宛先を読むために [ServiceCharacters.FALLBACK] で読み、誤りとして残す。
 * - 解放文字の直後の 1 文字は、区切り・終端・解放文字であっても値の文字として読む。区切り・解放文字・終端でない文字の前の解放文字は誤り。
 * - セグメントの終端の直後の CR・LF だけは読み飛ばす(それ以外の位置の制御文字は、文字セットの検査で誤りになる)。
 * 文字セットの検査は、UNB を読んだ後に行う([io.eia.b2bgateway.domain.validation.InterchangeCheck])。
 */
public object EdifactLexer {
    private val TAG = Regex("^[A-Z]{3}$")

    public fun read(text: String): Lexed {
        val declared = ServiceCharacters.fromUna(text)
        val issues = mutableListOf<LexIssue>()
        val service =
            when {
                declared == null -> {
                    issues += LexIssue(null, null, null, LexIssueKind.UNA_MISSING)
                    ServiceCharacters.FALLBACK
                }

                !declared.isValid -> {
                    issues += LexIssue(null, null, null, LexIssueKind.UNA_INVALID)
                    ServiceCharacters.FALLBACK
                }

                else -> {
                    declared
                }
            }
        val start = if (declared != null) ServiceCharacters.UNA_LENGTH else 0
        val reader = Reader(text, start, service, issues)
        reader.run()
        return Lexed(service, declared?.una, reader.segments, issues, reader.terminated)
    }

    private class Reader(
        private val text: String,
        private val start: Int,
        private val service: ServiceCharacters,
        private val issues: MutableList<LexIssue>,
    ) {
        val segments = mutableListOf<Segment>()
        var terminated = true
        private val elements = mutableListOf<List<String>>()
        private val components = mutableListOf<String>()
        private val value = StringBuilder()
        private var atSegmentStart = true

        fun run() {
            var position = start
            while (position < text.length) position = step(position)
            terminated = atSegmentStart
        }

        /** [position] の文字を読み、次に読む位置を返す。交換の最後の解放文字(続く文字がない)は、終わりとして読む(書きかけ)。 */
        private fun step(position: Int): Int {
            val c = text[position]
            val skipNewline = atSegmentStart && (c == '\r' || c == '\n')
            if (!skipNewline) atSegmentStart = false
            return when {
                skipNewline -> {
                    position + 1
                }

                c == service.release -> {
                    release(position)
                }

                else -> {
                    when (c) {
                        service.component -> endComponent()
                        service.element -> endElement()
                        service.terminator -> endSegment()
                        else -> value.append(c)
                    }
                    position + 1
                }
            }
        }

        private fun release(position: Int): Int {
            val next = text.getOrNull(position + 1) ?: return text.length
            if (next !in service.escaped) issue(LexIssueKind.RELEASE_NOT_BEFORE_SERVICE_CHARACTER)
            value.append(next)
            return position + 2
        }

        private fun endComponent() {
            components += value.toString()
            value.clear()
        }

        private fun endElement() {
            endComponent()
            elements += components.toList()
            components.clear()
        }

        private fun endSegment() {
            endElement()
            val head = elements.first()
            val tag = head.first()
            val index = segments.size
            if (!TAG.matches(tag) || head.size > 1) issues += LexIssue(index, null, null, LexIssueKind.INVALID_TAG)
            segments += Segment(tag, elements.drop(1), index)
            elements.clear()
            atSegmentStart = true
        }

        /** 今のセグメントの、今のデータ要素・構成要素の位置で誤りを残す(1 から。タグの次のデータ要素が 1)。 */
        private fun issue(kind: LexIssueKind) {
            issues += LexIssue(segments.size, elements.size, components.size + 1, kind)
        }
    }
}
