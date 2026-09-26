package io.eia.tools.contract.rules

import io.eia.tools.contract.AvroDocument
import io.eia.tools.contract.Contracts
import io.eia.tools.contract.Rule
import io.eia.tools.contract.Violation
import org.apache.avro.Schema
import org.apache.avro.SchemaCompatibility
import kotlin.io.path.nameWithoutExtension

/** Avro の命名(INTEGRATION_STANDARDS §1)と、main の版に対する FULL 互換(§4、ADR-0014)の検査。 */
object AvroRules {
    const val EVENTS_NAMESPACE = "io.eia.events"
    private const val COMMON_NAMESPACE = "$EVENTS_NAMESPACE.common"

    fun checkNaming(contracts: Contracts): List<Violation> = contracts.avros.flatMap(::checkNaming)

    private fun checkNaming(document: AvroDocument): List<Violation> {
        val violations = mutableListOf<Violation>()
        val schema = document.schema
        val domain =
            document.file.parent.fileName
                .toString()
        val expectedNamespace = "$EVENTS_NAMESPACE.$domain"
        if (schema.type != Schema.Type.RECORD) {
            violations += Violation(document.path, Rule.NAMING_AVRO, "トップレベルの型は record にしてください(${schema.type})")
        }
        if (schema.namespace != expectedNamespace) {
            violations += Violation(document.path, Rule.NAMING_AVRO, "namespace '${schema.namespace}' は '$expectedNamespace' ではありません")
        }
        if (schema.name != document.file.nameWithoutExtension) {
            violations += Violation(document.path, Rule.NAMING_AVRO, "record 名 '${schema.name}' がファイル名と一致しません")
        }
        namedTypes(schema).forEach { named ->
            if (!Naming.isPascalCase(named.name)) {
                violations += Violation(document.path, Rule.NAMING_AVRO, "型名 '${named.fullName}' が PascalCase ではありません")
            }
            if (named.namespace != expectedNamespace && named.namespace != COMMON_NAMESPACE) {
                violations +=
                    Violation(
                        document.path,
                        Rule.NAMING_AVRO,
                        "型 '${named.fullName}' の namespace は '$expectedNamespace' か '$COMMON_NAMESPACE' にしてください",
                    )
            }
        }
        return violations
    }

    /**
     * FULL 互換(ADR-0014): 次の両方を満たすこと。比較元は同じ相対パスの .avsc。比較元にないファイルは新規として検査しない。
     * - BACKWARD: 新しいスキーマ(reader)で、main のスキーマ(writer)で書かれたデータを読める(過去のイベントの Replay)
     * - FORWARD: main のスキーマ(reader)で、新しいスキーマ(writer)で書かれたデータを読める(発行側を先に更新できる)
     */
    fun checkFullCompatibility(
        current: Contracts,
        baseline: Contracts,
    ): List<Violation> {
        val previous = baseline.avros.associateBy { it.path }
        return current.avros.flatMap { document ->
            val old = previous[document.path] ?: return@flatMap emptyList()
            incompatibilities("BACKWARD", reader = document.schema, writer = old.schema, document.path) +
                incompatibilities("FORWARD", reader = old.schema, writer = document.schema, document.path)
        }
    }

    private fun incompatibilities(
        direction: String,
        reader: Schema,
        writer: Schema,
        path: String,
    ): List<Violation> {
        val result = SchemaCompatibility.checkReaderWriterCompatibility(reader, writer).result
        return if (result.compatibility == SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE) {
            emptyList()
        } else {
            result.incompatibilities.map {
                Violation(path, Rule.COMPAT_AVRO, "$direction: ${it.type} at ${it.location}: ${it.message}")
            }
        }
    }

    /** [schema] から到達できる名前付き型(record / enum / fixed)をすべて返す。 */
    fun namedTypes(schema: Schema): List<Schema> {
        val found = linkedMapOf<String, Schema>()

        fun visit(s: Schema) {
            val children =
                when (s.type) {
                    Schema.Type.RECORD -> if (found.putIfAbsent(s.fullName, s) == null) s.fields.map { it.schema() } else emptyList()
                    Schema.Type.ENUM, Schema.Type.FIXED -> emptyList<Schema>().also { found.putIfAbsent(s.fullName, s) }
                    Schema.Type.ARRAY -> listOf(s.elementType)
                    Schema.Type.MAP -> listOf(s.valueType)
                    Schema.Type.UNION -> s.types
                    else -> emptyList()
                }
            children.forEach(::visit)
        }
        visit(schema)
        return found.values.toList()
    }
}
