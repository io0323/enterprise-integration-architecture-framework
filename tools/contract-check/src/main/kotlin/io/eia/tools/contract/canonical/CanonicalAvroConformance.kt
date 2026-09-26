package io.eia.tools.contract.canonical

import io.eia.shared.kernel.money.Money
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.descriptors.nonNullOriginal
import org.apache.avro.LogicalTypes
import org.apache.avro.Schema

/**
 * Canonical Model(kotlinx.serialization の [SerialDescriptor])と Avro スキーマの一致検査(ADR-0012)。
 *
 * 項目名・必須性(nullable)・型を再帰的に比べる。次の 2 つは「決められた変換規則を持つ型」として特別に扱う。
 * - `Money` ⇔ Avro の record `Money { minorUnits: long, currency: string }`(JSON の 10 進文字列表現は別の物理表現で、対象外)
 * - `kotlin.time.Instant` ⇔ `long` + `timestamp-micros`(マイクロ秒未満は切り捨て)
 *
 * それ以外で変換規則のない型が現れたら、不一致として報告する。
 */
@OptIn(ExperimentalSerializationApi::class)
object CanonicalAvroConformance {
    private const val INSTANT_SERIAL_NAME = "kotlin.time.Instant"
    private const val MONEY_RECORD = "Money"
    private const val TIMESTAMP_MICROS = "timestamp-micros"

    private val PRIMITIVES =
        mapOf(
            PrimitiveKind.STRING to Schema.Type.STRING,
            PrimitiveKind.BOOLEAN to Schema.Type.BOOLEAN,
            PrimitiveKind.INT to Schema.Type.INT,
            PrimitiveKind.LONG to Schema.Type.LONG,
            PrimitiveKind.FLOAT to Schema.Type.FLOAT,
            PrimitiveKind.DOUBLE to Schema.Type.DOUBLE,
        )

    /** 不一致を「項目のパス: 内容」の形で返す。空なら一致。 */
    fun compare(
        descriptor: SerialDescriptor,
        schema: Schema,
    ): List<String> = compare(descriptor, schema, schema.name)

    private fun compare(
        descriptor: SerialDescriptor,
        schema: Schema,
        path: String,
    ): List<String> {
        val nullBranch = schema.type == Schema.Type.UNION && schema.types.any { it.type == Schema.Type.NULL }
        return when {
            descriptor.isNullable -> {
                val nonNull = if (nullBranch) schema.types.filterNot { it.type == Schema.Type.NULL } else emptyList()
                if (!nullBranch || nonNull.size != 1) {
                    listOf("$path: Kotlin は nullable ですが、Avro が [\"null\", T] の union ではありません(${schema.describe()})")
                } else {
                    compare(descriptor.nonNullOriginal, nonNull.single(), path)
                }
            }

            nullBranch -> {
                listOf("$path: Kotlin は必須(non-null)ですが、Avro は null を許します(${schema.describe()})")
            }

            else -> {
                compareNonNull(descriptor, schema, path)
            }
        }
    }

    private fun compareNonNull(
        descriptor: SerialDescriptor,
        schema: Schema,
        path: String,
    ): List<String> {
        val kind = descriptor.kind
        return when {
            kind == SerialKind.CONTEXTUAL && descriptor.capturedKClass == Money::class -> {
                compareMoney(schema, path)
            }

            descriptor.serialName == INSTANT_SERIAL_NAME -> {
                compareInstant(schema, path)
            }

            descriptor.isInline -> {
                compare(descriptor.getElementDescriptor(0), schema, path)
            }

            kind is PrimitiveKind -> {
                comparePrimitive(kind, schema, path)
            }

            kind == SerialKind.ENUM -> {
                compareEnum(descriptor, schema, path)
            }

            kind == StructureKind.LIST -> {
                if (schema.type != Schema.Type.ARRAY) {
                    listOf("$path: Kotlin は List ですが、Avro は ${schema.describe()} です")
                } else {
                    compare(descriptor.getElementDescriptor(0), schema.elementType, "$path[]")
                }
            }

            kind == StructureKind.MAP -> {
                compareMap(descriptor, schema, path)
            }

            kind == StructureKind.CLASS || kind == StructureKind.OBJECT -> {
                compareRecord(descriptor, schema, path)
            }

            else -> {
                listOf("$path: ${descriptor.serialName}(${descriptor.kind})には Avro への変換規則がありません(ADR-0012)")
            }
        }
    }

    private fun comparePrimitive(
        kind: PrimitiveKind,
        schema: Schema,
        path: String,
    ): List<String> {
        val expected = PRIMITIVES[kind] ?: return listOf("$path: $kind には Avro への変換規則がありません(ADR-0012)")
        return when {
            schema.type != expected -> listOf("$path: Kotlin は $kind ですが、Avro は ${schema.describe()} です(期待: ${expected.getName()})")
            schema.logicalType != null -> listOf("$path: Kotlin は $kind ですが、Avro は論理型 ${schema.logicalType.name} です")
            else -> emptyList()
        }
    }

    private fun compareEnum(
        descriptor: SerialDescriptor,
        schema: Schema,
        path: String,
    ): List<String> {
        if (schema.type != Schema.Type.ENUM) return listOf("$path: Kotlin は enum ですが、Avro は ${schema.describe()} です")
        val kotlinSymbols = descriptor.elementNames.toSet()
        val avroSymbols = schema.enumSymbols.toSet()
        return listOfNotNull(
            (kotlinSymbols - avroSymbols).takeIf { it.isNotEmpty() }?.let { "$path: Avro の enum に $it がありません" },
            (avroSymbols - kotlinSymbols).takeIf { it.isNotEmpty() }?.let { "$path: Kotlin の enum に $it がありません" },
        )
    }

    private fun compareMap(
        descriptor: SerialDescriptor,
        schema: Schema,
        path: String,
    ): List<String> {
        val key = descriptor.getElementDescriptor(0)
        return when {
            schema.type != Schema.Type.MAP -> listOf("$path: Kotlin は Map ですが、Avro は ${schema.describe()} です")
            key.kind != PrimitiveKind.STRING -> listOf("$path: Avro の map のキーは string のみです(Kotlin: ${key.serialName})")
            else -> compare(descriptor.getElementDescriptor(1), schema.valueType, "$path{}")
        }
    }

    private fun compareRecord(
        descriptor: SerialDescriptor,
        schema: Schema,
        path: String,
    ): List<String> {
        if (schema.type != Schema.Type.RECORD) {
            return listOf("$path: Kotlin は ${descriptor.serialName} ですが、Avro は ${schema.describe()} です")
        }
        val kotlinFields = (0 until descriptor.elementsCount).associateBy { descriptor.getElementName(it) }
        val avroFields = schema.fields.associateBy { it.name() }
        val missingInAvro = (kotlinFields.keys - avroFields.keys).map { "$path.$it: Avro にありません" }
        val missingInKotlin = (avroFields.keys - kotlinFields.keys).map { "$path.$it: Kotlin(${descriptor.serialName})にありません" }
        val mismatches =
            kotlinFields.filterKeys { it in avroFields }.flatMap { (name, index) ->
                val field = avroFields.getValue(name)
                val fieldPath = "$path.$name"
                val defaultMismatch =
                    if (descriptor.isElementOptional(index) && !field.hasDefaultValue()) {
                        listOf("$fieldPath: Kotlin は既定値を持つ任意項目ですが、Avro に default がありません")
                    } else {
                        emptyList()
                    }
                compare(descriptor.getElementDescriptor(index), field.schema(), fieldPath) + defaultMismatch
            }
        return missingInAvro + missingInKotlin + mismatches
    }

    private fun compareMoney(
        schema: Schema,
        path: String,
    ): List<String> {
        val expected = "record $MONEY_RECORD { minorUnits: long, currency: string }"
        if (schema.type != Schema.Type.RECORD || schema.name != MONEY_RECORD) {
            return listOf("$path: Money は Avro の $expected で表します(実際: ${schema.describe()})")
        }
        val fields = schema.fields.associate { it.name() to it.schema() }
        val ok =
            fields.keys == setOf("minorUnits", "currency") &&
                fields.getValue("minorUnits").let { it.type == Schema.Type.LONG && it.logicalType == null } &&
                fields.getValue("currency").type == Schema.Type.STRING
        return if (ok) emptyList() else listOf("$path: Money は Avro の $expected で表します(実際: ${schema.describe()})")
    }

    private fun compareInstant(
        schema: Schema,
        path: String,
    ): List<String> =
        if (schema.type == Schema.Type.LONG && schema.logicalType is LogicalTypes.TimestampMicros) {
            emptyList()
        } else {
            listOf("$path: Instant は Avro の long + $TIMESTAMP_MICROS で表します(実際: ${schema.describe()})")
        }

    private fun Schema.describe(): String =
        when (type) {
            Schema.Type.RECORD, Schema.Type.ENUM, Schema.Type.FIXED -> "${type.getName()} $fullName"
            Schema.Type.UNION -> types.joinToString(prefix = "[", postfix = "]") { it.describe() }
            else -> listOfNotNull(type.getName(), logicalType?.name).joinToString("+")
        }
}
