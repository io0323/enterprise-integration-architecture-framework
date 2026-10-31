package io.eia.b2bgateway.domain.edifact

/**
 * セグメントを EDIFACT の文字列にする(応答の書き出し。ADR-0041 §5)。先頭に UNA を付け、値の中の区切り・解放文字・終端の前に解放文字を置く。
 * 末尾の空の構成要素と、末尾の空のデータ要素は書かない(ISO 9735 の省略の規則)。セグメントの間に改行は入れない。
 */
public object EdifactWriter {
    public fun write(
        service: ServiceCharacters,
        segments: List<Segment>,
    ): String =
        buildString {
            append(service.una)
            segments.forEach { segment ->
                append(segment.tag)
                segment.elements
                    .map { element ->
                        element.dropLastWhile { it.isEmpty() }
                    }.dropLastWhile { it.isEmpty() }
                    .forEach { element ->
                        append(service.element)
                        element.forEachIndexed { index, value ->
                            if (index > 0) append(service.component)
                            value.forEach { c ->
                                if (c in service.escaped) append(service.release)
                                append(c)
                            }
                        }
                    }
                append(service.terminator)
            }
        }
}
