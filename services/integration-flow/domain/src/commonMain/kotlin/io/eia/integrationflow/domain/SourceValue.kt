package io.eia.integrationflow.domain

/**
 * 元のレコード(SaaS の JSON)の値。adapters が JSON から作る(domain は JSON の部品に依存しない)。
 * 数は元の表記のまま持つ([Number.raw])。整数として読むかどうかは使う側が決める。
 */
public sealed interface SourceValue {
    public data class Text(
        val value: String,
    ) : SourceValue {
        // 個人情報を含みうるので値を出さない
        override fun toString(): String = "Text(***)"
    }

    public data class Number(
        val raw: String,
    ) : SourceValue

    public data class Bool(
        val value: Boolean,
    ) : SourceValue

    public data object Null : SourceValue

    public data class Obj(
        val fields: Map<String, SourceValue>,
    ) : SourceValue {
        override fun toString(): String = "Obj(${fields.keys})"
    }

    public data class Arr(
        val items: List<SourceValue>,
    ) : SourceValue {
        override fun toString(): String = "Arr(${items.size})"
    }
}

/**
 * 元のレコードの中の場所(`$` と `.項目名` の並びだけ。配列の添字・フィルタ・関数は使えない。ADR-0036 §2)。
 * 途中に項目がない・オブジェクトでないときは、値がない(null)とする。
 */
public data class SourcePath private constructor(
    val text: String,
    private val names: List<String>,
) {
    public fun read(record: SourceValue): SourceValue? =
        names
            .fold(record as SourceValue?) { current, name -> (current as? SourceValue.Obj)?.fields?.get(name) }
            ?.takeUnless { it == SourceValue.Null }

    override fun toString(): String = text

    public companion object {
        private val PATTERN = Regex("^\\$(\\.[A-Za-z_][A-Za-z0-9_]*)*$")

        /** 形が誤っていれば null。 */
        public fun parse(text: String): SourcePath? =
            if (PATTERN.matches(text)) SourcePath(text, text.removePrefix("$").split('.').filter { it.isNotEmpty() }) else null
    }
}
