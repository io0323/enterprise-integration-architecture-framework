package io.eia.b2bgateway.application

import io.eia.b2bgateway.application.port.inbound.Receipt
import io.eia.b2bgateway.application.port.inbound.ReceivedDocument
import io.eia.b2bgateway.application.port.inbound.UnknownPartner
import io.eia.b2bgateway.application.port.outbound.CodeLookup
import io.eia.b2bgateway.application.port.outbound.CodeMappings
import io.eia.b2bgateway.application.port.outbound.ControlReferences
import io.eia.b2bgateway.application.port.outbound.EdiSpecs
import io.eia.b2bgateway.application.port.outbound.PartnerAgreements
import io.eia.b2bgateway.application.port.outbound.ReceivedInterchanges
import io.eia.b2bgateway.application.port.outbound.ReceivedOrders
import io.eia.b2bgateway.application.usecase.ReceiveInterchangeService
import io.eia.b2bgateway.application.usecase.ReceivingPorts
import io.eia.b2bgateway.domain.agreement.PartnerAgreement
import io.eia.b2bgateway.domain.edifact.CharacterSet
import io.eia.b2bgateway.domain.edifact.Latin1
import io.eia.b2bgateway.domain.order.BusinessRule
import io.eia.b2bgateway.domain.order.CodeResolution
import io.eia.b2bgateway.domain.order.InternalCustomer
import io.eia.b2bgateway.domain.order.InternalItem
import io.eia.b2bgateway.domain.order.ItemCode
import io.eia.b2bgateway.domain.order.ShippingAddress
import io.eia.b2bgateway.domain.receipt.DuplicateCheck
import io.eia.b2bgateway.domain.receipt.InterchangeKey
import io.eia.b2bgateway.domain.receipt.PreviousReceipt
import io.eia.b2bgateway.domain.spec.DataFormat
import io.eia.b2bgateway.domain.spec.Direction
import io.eia.b2bgateway.domain.spec.EdiSpec
import io.eia.b2bgateway.domain.spec.ElementDefinition
import io.eia.b2bgateway.domain.spec.MessageIdentifier
import io.eia.b2bgateway.domain.spec.RuleText
import io.eia.b2bgateway.domain.spec.SegmentDefinition
import io.eia.b2bgateway.domain.spec.Status
import io.eia.b2bgateway.domain.spec.StructureNode
import io.eia.b2bgateway.domain.spec.SyntaxRules
import io.eia.b2bgateway.domain.validation.InterchangeResult
import io.eia.b2bgateway.domain.validation.PartyIdentification
import io.eia.b2bgateway.domain.validation.ServiceError
import io.eia.b2bgateway.domain.validation.SyntaxErrorCode
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.FixedClock
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** どの値も受け付けるデータ要素(位置で読むだけ。値の検査は domain と adapters のテストで行う)。 */
private fun loose(tag: String): SegmentDefinition =
    SegmentDefinition(
        tag,
        // UNB の 11 個まで(試験の印 0035 の位置)
        List(11) { index ->
            ElementDefinition.Composite(
                "C${index.toString().padStart(3, '0')}",
                Status.CONDITIONAL,
                List(4) { ElementDefinition.Simple("000$it", Status.CONDITIONAL, checkNotNull(DataFormat.parse("an..35"))) },
            )
        },
    )

private fun spec(
    type: String,
    release: String,
    structure: List<StructureNode> = emptyList(),
    tags: List<String> = emptyList(),
    rules: List<RuleText> = emptyList(),
): EdiSpec =
    EdiSpec(
        name = "test-$type",
        version = 1,
        direction = Direction.INBOUND,
        message = MessageIdentifier(type, "D", release, "UN"),
        syntax = SyntaxRules(setOf(3), setOf(CharacterSet.UNOA, CharacterSet.UNOC), 10),
        segments = (listOf("UNB", "UNH", "UNT", "UNZ") + tags).associateWith(::loose),
        structure = structure,
        businessRules = rules,
        errorTexts = listOf(RuleText("APR901", "ORDER NOT ACCEPTED BY SUPPLIER SYSTEM")),
    )

private fun segment(
    tag: String,
    max: Int = 1,
    mandatory: Boolean = true,
) = StructureNode.SegmentNode(tag, mandatory, max)

private object Specs : EdiSpecs {
    override val orders: EdiSpec =
        spec(
            "ORDERS",
            "96A",
            structure =
                listOf(
                    segment("BGM"),
                    segment("DTM", max = 2),
                    StructureNode.GroupNode("SG2", true, 3, listOf(segment("NAD"))),
                    segment("CUX"),
                    StructureNode.GroupNode(
                        "SG25",
                        true,
                        99,
                        listOf(segment("LIN"), segment("PIA", mandatory = false), segment("QTY"), segment("PRI")),
                    ),
                    segment("CNT", mandatory = false),
                ),
            tags = listOf("BGM", "DTM", "NAD", "CUX", "LIN", "PIA", "QTY", "PRI", "CNT"),
            rules = BusinessRule.entries.map { RuleText(it.code, it.name.replace('_', ' ')) },
        )
    override val contrl: EdiSpec = spec("CONTRL", "3")
    override val aperak: EdiSpec = spec("APERAK", "96A")
}

private val AGREEMENT =
    PartnerAgreement(
        partnerId = "partner01",
        sender = PartyIdentification("S", "14"),
        recipient = PartyIdentification("R", "14"),
        ownGln = "R-GLN",
        currencies = setOf(Currency.JPY),
        positiveAperak = true,
        aperakDeadline = 4.hours,
    )

private val RESOLUTION =
    CodeResolution(
        buyers = mapOf("B" to InternalCustomer("cust-1", ShippingAddress("JP", "100-0001", null, "Chiyoda", "1-1", null))),
        items =
            mapOf(ItemCode.Gtin("G1") to InternalItem("prod-1", "SKU-1")),
    )

private class Fakes(
    var agreement: PartnerAgreement? = AGREEMENT,
    var previous: PreviousReceipt? = null,
    var receivedOrders: Set<String> = emptySet(),
    var failure: DomainError? = null,
) : PartnerAgreements,
    ReceivedInterchanges,
    CodeMappings,
    ReceivedOrders,
    ControlReferences {
    val keys = mutableListOf<InterchangeKey>()
    val lookups = mutableListOf<CodeLookup>()
    var references = 0

    private fun <T> answer(value: T): Result<T, DomainError> = failure?.let { err(it) } ?: ok(value)

    override suspend fun find(partnerId: String): Result<PartnerAgreement?, DomainError> =
        ok(agreement?.takeIf { it.partnerId == partnerId })

    override suspend fun find(key: InterchangeKey): Result<PreviousReceipt?, DomainError> {
        keys += key
        return answer(previous)
    }

    override suspend fun resolve(
        partnerId: String,
        lookup: CodeLookup,
    ): Result<CodeResolution, DomainError> {
        lookups += lookup
        return answer(RESOLUTION)
    }

    override suspend fun exists(
        partnerId: String,
        orderNumber: String,
    ): Result<Boolean, DomainError> = ok(orderNumber in receivedOrders)

    override suspend fun next(recipient: PartyIdentification): Result<String, DomainError> = ok("R-${++references}")

    fun service() =
        ReceiveInterchangeService(Specs, ReceivingPorts(this, this, this, this, this), FixedClock(Instant.parse("2026-10-31T00:00:00Z")))
}

private data object StoreDown : DomainError.Retryable {
    override val code: String = "store_down"
    override val message: String = "記録を読めません"
}

private fun order(
    number: String = "PO-1",
    buyer: String = "B",
): List<String> =
    listOf(
        "BGM+220+$number+9",
        "DTM+137:20261030:102",
        "NAD+BY+$buyer::9",
        "NAD+SU+R-GLN::9",
        "CUX+2:JPY:9",
        "LIN+1++G1:EN",
        "QTY+21:10",
        "PRI+AAA:1200",
    )

private fun interchange(
    vararg messages: List<String>,
    unb: String = "UNB+UNOA:3+S:14+R:14+261030:1200+ICR",
): String {
    val segments =
        listOf(unb) +
            messages.flatMapIndexed {
                index,
                body,
                ->
                listOf("UNH+${index + 1}+ORDERS:D:96A:UN") + body + "UNT+${body.size + 2}+${index + 1}"
            } +
            "UNZ+${messages.size}+ICR"
    return "UNA:+.? '" + segments.joinToString("'", postfix = "'")
}

private suspend fun Fakes.receive(text: String): Result<Receipt, DomainError> =
    service()(ReceivedDocument("partner01", Latin1.encode(text), "sha-1"))

private suspend fun Fakes.receipt(text: String): Receipt = receive(text).shouldBeInstanceOf<Result.Ok<Receipt>>().value

private fun ByteArray?.text(): String = Latin1.decode(checkNotNull(this))

class ReceiveInterchangeSpec :
    FunSpec({
        test("業務の受け付け: CONTRL(UCI = 7・UCM = 7)だけを返し、肯定の APERAK は社内に取り込んだ後(P12 ⑤)に回す") {
            val fakes = Fakes()

            val receipt = fakes.receipt(interchange(order()))

            receipt.duplicate shouldBe DuplicateCheck.First
            receipt.orders.single().accepted shouldBe true
            receipt.orders.single().resolution shouldBe RESOLUTION
            receipt.contrl.text() shouldContain "UCI+ICR+S:14+R:14+7'UCM+1+ORDERS:D:96A:UN+7'"
            receipt.aperak shouldBe null
            fakes.keys shouldContainExactly listOf(InterchangeKey("partner01", AGREEMENT.sender, AGREEMENT.recipient, "ICR"))
            fakes.lookups shouldContainExactly listOf(CodeLookup(setOf("B"), emptySet(), setOf(ItemCode.Gtin("G1"))))
        }

        test("業務の拒否: 拒否した注文にだけ APERAK(RE)を返す(文言は仕様から)。受け付けた注文は返さない") {
            val receipt = Fakes(receivedOrders = setOf("PO-OLD")).receipt(interchange(order(buyer = "X"), order("PO-OLD"), order("PO-3")))

            receipt.orders.map { it.violations } shouldContainExactly
                listOf(listOf(BusinessRule.UNKNOWN_BUYER), listOf(BusinessRule.DUPLICATE_ORDER_NUMBER), emptyList())
            receipt.contrl.text() shouldContain "UCM+3+ORDERS:D:96A:UN+7'"
            val aperak = receipt.aperak.text()
            aperak shouldContain "BGM+23+R-2-1+9+RE'DTM+137:20261031:102'RFF+CO:PO-1'ERC+ORD001::ZZZ'FTX+AAO+++UNKNOWN BUYER'"
            aperak shouldContain "RFF+CO:PO-OLD'ERC+ORD007::ZZZ'FTX+AAO+++DUPLICATE ORDER NUMBER'"
            aperak shouldContain "UNZ+2+R-2'"
        }

        test("同じ交換の中の同じ注文番号は、2 つ目を重複にする") {
            Fakes().receipt(interchange(order(), order())).orders.map { it.violations } shouldContainExactly
                listOf(emptyList(), listOf(BusinessRule.DUPLICATE_ORDER_NUMBER))
        }

        test("試験の交換は、取引先が選んでいれば受け付けの APERAK(AP)をここで返す(社内に取り込まないため)") {
            val test = interchange(order(), unb = "UNB+UNOA:3+S:14+R:14+261030:1200+ICR++++++1")

            Fakes().receipt(test).aperak.text() shouldContain "++++++1'UNH+1+APERAK:D:96A:UN'BGM+23+R-2-1+9+AP'"
            Fakes(agreement = AGREEMENT.copy(positiveAperak = false)).receipt(test).aperak shouldBe null
        }

        test("スキーマの段階で拒否したメッセージは、業務の段階に進めない") {
            val fakes = Fakes()

            val receipt = fakes.receipt(interchange(order().filterNot { it.startsWith("CUX") }, order("PO-2")))

            receipt.orders.map { it.messageReference } shouldContainExactly listOf("2")
            receipt.contrl.text() shouldContain "UCM+1+ORDERS:D:96A:UN+4'"
        }

        context("交換の拒否(ADR-0041 §4・§11)") {
            test("送り手が取引先の台帳の識別子と違う → 23、受け手が自社でない → 7。業務の段階には進めない") {
                listOf(
                    "UNB+UNOA:3+T:14+R:14+261030:1200+ICR" to ServiceError(SyntaxErrorCode.UNKNOWN_INTERCHANGE_SENDER, "UNB", 2, 1),
                    "UNB+UNOA:3+S:ZZ+R:14+261030:1200+ICR" to ServiceError(SyntaxErrorCode.UNKNOWN_INTERCHANGE_SENDER, "UNB", 2, 1),
                    "UNB+UNOA:3+S:14+Q:14+261030:1200+ICR" to ServiceError(SyntaxErrorCode.RECIPIENT_NOT_ACTUAL_RECIPIENT, "UNB", 3, 1),
                ).forEach { (unb, error) ->
                    val receipt = Fakes().receipt(interchange(order(), unb = unb))
                    receipt.result.shouldBeInstanceOf<InterchangeResult.Rejected>().error shouldBe error
                    receipt.orders.shouldBeEmpty()
                    receipt.aperak shouldBe null
                }
            }

            test("構文の誤りは、台帳との照合より先に報告する") {
                Fakes()
                    .receipt(
                        interchange(order(), unb = "UNB+UNOA:4+T:14+R:14+261030:1200+ICR"),
                    ).result
                    .shouldBeInstanceOf<InterchangeResult.Rejected>()
                    .error shouldBe
                    ServiceError(SyntaxErrorCode.UNSUPPORTED_SYNTAX_VERSION, "UNB", 1, 2)
            }

            test("同じ管理番号で中身が違う(使い回し)→ 26。構文の誤りより先に報告する") {
                val receipt =
                    Fakes(
                        previous = PreviousReceipt("other", "doc-1"),
                    ).receipt(interchange(order()).replace("UNOA:3", "UNOA:4"))

                receipt.duplicate shouldBe DuplicateCheck.Reused
                receipt.result.shouldBeInstanceOf<InterchangeResult.Rejected>().error shouldBe
                    ServiceError(SyntaxErrorCode.DUPLICATE_DETECTED, "UNB", 5)
                receipt.contrl.text() shouldContain "UCI+ICR+S:14+R:14+4+26+UNB+5'"
            }
        }

        test("同じ管理番号・同じ中身の送り直し: 取り込まず、新しい応答も作らない(前の記録の応答を送り直す。管理番号も振らない)") {
            val fakes = Fakes(previous = PreviousReceipt("sha-1", "doc-1"))

            val receipt = fakes.receipt(interchange(order()))

            receipt.duplicate shouldBe DuplicateCheck.Resend(PreviousReceipt("sha-1", "doc-1"))
            receipt.orders.shouldBeEmpty()
            receipt.contrl shouldBe null
            receipt.aperak shouldBe null
            fakes.references shouldBe 0
            fakes.lookups.shouldBeEmpty()
        }

        test("UNB を読めない交換: 重複の判定も応答もしない") {
            val fakes = Fakes()

            val receipt = fakes.receipt("UNA:+.? 'BGM+220'")

            receipt.result.shouldBeInstanceOf<InterchangeResult.Unreadable>()
            receipt.contrl shouldBe null
            fakes.keys.shouldBeEmpty()
        }

        test("取引先の ID が台帳にない → UnknownPartner(設定の誤り)。Port のエラーはそのまま返す") {
            Fakes(agreement = null).receive(interchange(order())) shouldBe err(UnknownPartner("partner01"))
            Fakes(failure = StoreDown).receive(interchange(order())) shouldBe err(StoreDown)
        }
    })
