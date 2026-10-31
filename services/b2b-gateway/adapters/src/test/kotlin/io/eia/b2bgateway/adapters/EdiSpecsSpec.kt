package io.eia.b2bgateway.adapters

import io.eia.b2bgateway.adapters.spec.YamlEdiSpecs
import io.eia.b2bgateway.domain.ack.Contrl
import io.eia.b2bgateway.domain.ack.ResponseEnvelope
import io.eia.b2bgateway.domain.edifact.CharacterSet
import io.eia.b2bgateway.domain.edifact.EdifactLexer
import io.eia.b2bgateway.domain.edifact.Latin1
import io.eia.b2bgateway.domain.edifact.Segment
import io.eia.b2bgateway.domain.spec.Direction
import io.eia.b2bgateway.domain.spec.ElementDefinition
import io.eia.b2bgateway.domain.validation.ElementError
import io.eia.b2bgateway.domain.validation.InterchangeCheck
import io.eia.b2bgateway.domain.validation.InterchangeResult
import io.eia.b2bgateway.domain.validation.SegmentError
import io.eia.b2bgateway.domain.validation.ServiceError
import io.eia.b2bgateway.domain.validation.SyntaxErrorCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.time.Instant

private val specs = YamlEdiSpecs()
private val ENVELOPE = ResponseEnvelope("R-1", "1", Instant.parse("2026-10-31T00:00:00Z"))

/** 受注の文書の正しい交換(契約の仕様 edifact-orders.v1 のサンプル。明細 2 つ)。[body] で本文を差し替えられる。 */
private fun orders(
    body: List<String> = VALID_ORDER,
    unb: String = "UNB+UNOA:3+4912345000019:14+4900000000001:14+261030:1200+ICR0001",
    una: String = "UNA:+.? '",
): String =
    una + (listOf(unb, "UNH+1+ORDERS:D:96A:UN") + body + listOf("UNT+${body.size + 2}+1", "UNZ+1+ICR0001")).joinToString("'", postfix = "'")

private val VALID_ORDER =
    listOf(
        "BGM+220+PO-1001+9",
        "DTM+137:20261030:102",
        "DTM+2:20261105:102",
        "NAD+BY+4912345000019::9",
        "NAD+SU+4900000000001::9",
        "NAD+DP+4912345000026::9",
        "CUX+2:JPY:9",
        "LIN+1++4901234567894:EN",
        "PIA+1+ABC-123:BP",
        "QTY+21:10",
        "PRI+AAA:1200",
        "LIN+2++4901234567900:EN",
        "QTY+21:5",
        "PRI+AAA:980.5",
        "UNS+S",
        "CNT+2:2",
    )

private fun check(text: String): InterchangeResult = InterchangeCheck.check(EdifactLexer.read(text), specs.orders)

private fun message(text: String) = check(text).shouldBeInstanceOf<InterchangeResult.Checked>().messages.single()

/** 結果から CONTRL を組み立て、文字列にする。 */
private fun contrl(result: InterchangeResult): String {
    val header =
        when (result) {
            is InterchangeResult.Rejected -> result.header
            is InterchangeResult.Checked -> result.header
            is InterchangeResult.Unreadable -> error("宛先がない")
        }
    return Latin1.decode(Contrl.write(header, checkNotNull(Contrl.build(specs.contrl, result, ENVELOPE))))
}

/** CONTRL を、契約の CONTRL の仕様で検査する(自分の出す応答が、自分の仕様に従うこと)。 */
private fun shouldConformToContrlSpec(text: String) {
    val result = InterchangeCheck.check(EdifactLexer.read(text), specs.contrl).shouldBeInstanceOf<InterchangeResult.Checked>()
    result.messages.single().header shouldBe null
    result.messages
        .single()
        .errors
        .shouldBeEmpty()
}

/**
 * 取引先に返す CONTRL に、標準のコードと位置と、受け取った識別子のほかの値がないこと(ADR-0041 §5)。
 * UCS・UCD の値はすべて数字、UCI・UCM の 0083 以降は数字かサービスセグメントのタグ。
 */
private fun shouldContainOnlyCodesAndPositions(text: String) {
    val codeOrPosition = Regex("^([0-9]{1,6}|UN[A-Z])?$")
    EdifactLexer.read(text).segments.forEach { segment: Segment ->
        val checked =
            when (segment.tag) {
                "UCS", "UCD" -> segment.elements
                "UCI" -> segment.elements.drop(3)
                "UCM" -> segment.elements.drop(2)
                else -> emptyList()
            }
        checked.flatten().filterNot { codeOrPosition.matches(it) } shouldBe emptyList()
    }
}

class EdiSpecsSpec :
    FunSpec({
        context("仕様の読み込み(ADR-0041 §7)") {
            test("リソースの仕様は、契約のファイル(contracts/files/edi)と同じ") {
                val contracts = Path.of(System.getProperty("eia.contractsEdi"))
                val files = contracts.listDirectoryEntries("*.yaml")

                files.map { it.name }.sorted() shouldContainExactly listOf(YamlEdiSpecs.APERAK, YamlEdiSpecs.CONTRL, YamlEdiSpecs.ORDERS)
                files.forEach { file ->
                    YamlEdiSpecs::class.java.getResource(YamlEdiSpecs.RESOURCE_DIR + file.name)!!.readText() shouldBe file.readText()
                }
            }

            test("3 つの仕様を読む") {
                with(specs.orders) {
                    direction shouldBe Direction.INBOUND
                    message.values shouldContainExactly listOf("ORDERS", "D", "96A", "UN")
                    syntax.characterSets shouldBe setOf(CharacterSet.UNOA, CharacterSet.UNOB, CharacterSet.UNOC)
                    businessRules.map { it.code }.first() shouldBe "ORD001"
                }
                specs.contrl.message.values shouldContainExactly listOf("CONTRL", "D", "3", "UN")
                YamlEdiSpecs
                    .load(YamlEdiSpecs.APERAK)
                    .errorTexts
                    .single()
                    .code shouldBe "APR901"
            }

            test("使う構文の誤りのコード(SyntaxErrorCode)は、CONTRL の仕様の 0085 の codes と一致する") {
                val uci =
                    specs.contrl.segments
                        .getValue("UCI")
                        .elements
                val codes = uci.filterIsInstance<ElementDefinition.Simple>().single { it.id == "0085" }.codes

                SyntaxErrorCode.entries.map { it.code }.toSet() shouldBe codes
            }
        }

        context("契約の仕様での検査と、CONTRL") {
            test("正しい受注の文書は受け付け、CONTRL(UCI = 7・UCM = 7)は CONTRL の仕様に従う") {
                val result = check(orders())
                result
                    .shouldBeInstanceOf<InterchangeResult.Checked>()
                    .messages
                    .single()
                    .accepted shouldBe true

                val text = contrl(result)
                text shouldBe
                    "UNA:+.? 'UNB+UNOA:3+4900000000001:14+4912345000019:14+261031:0000+R-1'UNH+1+CONTRL:D:3:UN'" +
                    "UCI+ICR0001+4912345000019:14+4900000000001:14+7'UCM+1+ORDERS:D:96A:UN+7'UNT+4+1'UNZ+1+R-1'"
                shouldConformToContrlSpec(text)
            }

            test("UNOC の交換は、アクセント記号のある文字を受け付ける") {
                message(
                    orders(
                        VALID_ORDER.map {
                            it.replace("PO-1001", "PO-Café")
                        },
                        unb = "UNB+UNOC:3+4912345000019:14+4900000000001:14+261030:1200+ICR0001",
                    ),
                ).accepted shouldBe true
            }

            test("スキーマの誤り(必須の修飾子・日付・コード値・文字セット・並び)の位置を返し、CONTRL は仕様に従い、コードと位置だけを含む") {
                val body =
                    VALID_ORDER
                        .filterNot { it.startsWith("DTM+137") }
                        .map { it.replace("20261105", "20261131").replace("CUX+2:JPY:9", "CUX+2:JPY:8").replace("PO-1001", "po-1001") }
                val result = check(orders(body))

                result
                    .shouldBeInstanceOf<InterchangeResult.Checked>()
                    .messages
                    .single()
                    .errors shouldContainExactly
                    listOf(
                        SegmentError(2, null, listOf(ElementError(SyntaxErrorCode.INVALID_CHARACTERS, 2))),
                        SegmentError(3, SyntaxErrorCode.MISSING, listOf(ElementError(SyntaxErrorCode.INVALID_VALUE, 1, 2))),
                        SegmentError(7, null, listOf(ElementError(SyntaxErrorCode.INVALID_VALUE, 1, 3))),
                    )
                val text = contrl(result)
                shouldConformToContrlSpec(text)
                shouldContainOnlyCodesAndPositions(text)
            }

            test("明細の価格(SG28)がない・グループの回数の超過") {
                message(orders(VALID_ORDER.filterNot { it == "PRI+AAA:1200" })).errors shouldContainExactly
                    listOf(SegmentError(12, SyntaxErrorCode.MISSING))
                message(orders(VALID_ORDER.take(7) + "CUX+2:JPY:9" + VALID_ORDER.drop(7))).errors shouldContainExactly
                    listOf(SegmentError(9, SyntaxErrorCode.TOO_MANY_GROUP_REPETITIONS))
            }

            test("交換の誤り(UNA がない・構文の版が 4)の CONTRL も仕様に従う") {
                listOf(
                    orders(una = "") to ServiceError(SyntaxErrorCode.MISSING, "UNA"),
                    orders(unb = "UNB+UNOA:4+4912345000019:14+4900000000001:14+261030:1200+ICR0001") to
                        ServiceError(SyntaxErrorCode.UNSUPPORTED_SYNTAX_VERSION, "UNB", 1, 2),
                ).forEach { (text, error) ->
                    val result = check(text).shouldBeInstanceOf<InterchangeResult.Rejected>()
                    result.error shouldBe error
                    val response = contrl(result)
                    shouldConformToContrlSpec(response)
                    shouldContainOnlyCodesAndPositions(response)
                }
            }
        }
    })
