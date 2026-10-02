package io.eia.platform.schemaregistry

/**
 * トピックの値(value)のスキーマ。レジストリには `{groupId}/{topic}-value` のアーティファクトとして登録する。
 * アーティファクトの名前は Apicurio の公式の Serde の既定(`TopicIdStrategy`)と同じで、公式の Serde を使う相手とも相互運用できる(ADR-0025 §2)。
 *
 * @property topic トピック名(例 `sales.order.created.v1`)
 * @property schema Avro スキーマの JSON(`contracts/avro` のファイルの内容)。レジストリは正規化して比べるため、空白や項目の順序は問わない
 */
public data class SchemaSubject(
    public val topic: String,
    public val schema: String,
) {
    init {
        require(topic.isNotBlank()) { "トピック名が空です" }
        require(schema.isNotBlank()) { "$topic のスキーマが空です" }
    }

    /** レジストリのアーティファクト ID。 */
    public val artifactId: String get() = "$topic$VALUE_SUFFIX"

    // スキーマの全文はログやエラーに出さない(長く、読み手に役立たない)
    override fun toString(): String = "SchemaSubject(topic=$topic)"

    private companion object {
        const val VALUE_SUFFIX = "-value"
    }
}

/**
 * レジストリのスキーマの内容の ID(Apicurio の contentId)。同じ内容は、どのアーティファクト・どの版でも同じ ID になる。
 * wire format(ADR-0025 §1)では 4 バイトの符号付き整数で運ぶため、その範囲に限る。
 */
@JvmInline
public value class ContentId(
    public val value: Long,
) {
    init {
        require(value in 0..Int.MAX_VALUE) { "contentId が 4 バイトの範囲を超えています: $value" }
    }

    override fun toString(): String = value.toString()
}
