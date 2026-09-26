package io.eia.tools.contract

import io.eia.shared.canonical.sales.Order
import io.eia.shared.kernel.money.Money
import io.eia.tools.contract.canonical.CanonicalAvroConformance
import io.eia.tools.contract.canonical.CanonicalBindings
import io.eia.tools.contract.rules.AvroRules
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.apache.avro.Schema
import kotlin.time.Instant

@Serializable
private data class Sample(
    val name: String,
    val count: Int,
    val tags: List<String>,
    val attributes: Map<String, Long>,
    @Contextual val price: Money,
    val at: Instant,
    val note: String? = null,
)

@Serializable
private data class WithUnsupported(
    val ratio: Char,
)

private fun avro(json: String): Schema = Schema.Parser().parse(json)

private const val MONEY = """{"type":"record","name":"Money","namespace":"io.eia.events.common","fields":[
    {"name":"minorUnits","type":"long"},{"name":"currency","type":"string"}]}"""

private fun sampleSchema(
    count: String = "\"int\"",
    at: String = """{"type":"long","logicalType":"timestamp-micros"}""",
    price: String = MONEY,
    note: String = """{"name":"note","type":["null","string"],"default":null}""",
    extra: String = "",
): Schema =
    avro(
        """{"type":"record","name":"Sample","namespace":"io.eia.events.test","fields":[
        {"name":"name","type":"string"},{"name":"count","type":$count},
        {"name":"tags","type":{"type":"array","items":"string"}},
        {"name":"attributes","type":{"type":"map","values":"long"}},
        {"name":"price","type":$price},{"name":"at","type":$at},$note$extra]}""",
    )

class CanonicalAvroConformanceSpec :
    FunSpec({
        test("リポジトリの Avro は Canonical Model の Order と一致する") {
            val contracts = Contracts.load(Workspace.REPOSITORY_ROOT)
            val order = contracts.avros.flatMap { AvroRules.namedTypes(it.schema) }.single { it.fullName == "io.eia.events.sales.Order" }

            CanonicalAvroConformance.compare(Order.serializer().descriptor, order).shouldBeEmpty()
            CanonicalBindings.ALL.map { it.avroFullName } shouldContainExactlyInAnyOrder listOf("io.eia.events.sales.Order")
        }

        test("変換規則(String / Int / List / Map / Money / Instant / nullable + default)に従えば一致する") {
            CanonicalAvroConformance.compare(Sample.serializer().descriptor, sampleSchema()).shouldBeEmpty()
        }

        test("Int を long で表すと不一致") {
            CanonicalAvroConformance.compare(Sample.serializer().descriptor, sampleSchema(count = "\"long\"")).single() shouldContain
                "Sample.count"
        }

        test("Instant を timestamp-millis や素の long で表すと不一致") {
            listOf("""{"type":"long","logicalType":"timestamp-millis"}""", "\"long\"", "\"string\"").forEach { at ->
                CanonicalAvroConformance.compare(Sample.serializer().descriptor, sampleSchema(at = at)).single() shouldContain
                    "timestamp-micros"
            }
        }

        test("Money を decimal や別の項目名で表すと不一致") {
            val decimal = """{"type":"bytes","logicalType":"decimal","precision":18,"scale":2}"""
            val renamed = MONEY.replace("minorUnits", "amountMinor")
            val millis = MONEY.replace("\"long\"", "\"int\"")
            listOf(decimal, renamed, millis).forEach { price ->
                CanonicalAvroConformance.compare(Sample.serializer().descriptor, sampleSchema(price = price)).single() shouldContain
                    "minorUnits: long"
            }
        }

        test("nullable と必須性・default の食い違いは不一致") {
            val required = """{"name":"note","type":"string"}"""
            val noDefault = """{"name":"note","type":["null","string"]}"""
            // 必須にすると、null を許さないことと default がないことの 2 点が食い違う
            val requiredMismatches = CanonicalAvroConformance.compare(Sample.serializer().descriptor, sampleSchema(note = required))
            requiredMismatches shouldHaveSize 2
            requiredMismatches[0] shouldContain "nullable"
            requiredMismatches[1] shouldContain "default"
            CanonicalAvroConformance.compare(Sample.serializer().descriptor, sampleSchema(note = noDefault)).single() shouldContain
                "default"
        }

        test("Avro にだけある項目・Kotlin にだけある項目は不一致") {
            val extra =
                CanonicalAvroConformance.compare(
                    Sample.serializer().descriptor,
                    sampleSchema(extra = """,{"name":"x","type":"int"}"""),
                )
            extra.single() shouldContain "Sample.x: Kotlin"
            val missing =
                CanonicalAvroConformance.compare(
                    Sample.serializer().descriptor,
                    sampleSchema(note = """{"name":"other","type":["null","string"],"default":null}"""),
                )
            missing shouldHaveSize 2
        }

        test("変換規則のない型は不一致として報告する") {
            val schema = avro("""{"type":"record","name":"WithUnsupported","fields":[{"name":"ratio","type":"string"}]}""")

            CanonicalAvroConformance.compare(WithUnsupported.serializer().descriptor, schema).single() shouldContain "ratio"
        }
    })
