package io.eia.b2bgateway.application

import io.eia.b2bgateway.application.port.inbound.SyntaxCheck
import io.eia.b2bgateway.application.port.outbound.ControlReferences
import io.eia.b2bgateway.application.port.outbound.EdiSpecs
import io.eia.b2bgateway.application.usecase.CheckInterchangeService
import io.eia.b2bgateway.domain.edifact.CharacterSet
import io.eia.b2bgateway.domain.edifact.Latin1
import io.eia.b2bgateway.domain.spec.DataFormat
import io.eia.b2bgateway.domain.spec.Direction
import io.eia.b2bgateway.domain.spec.EdiSpec
import io.eia.b2bgateway.domain.spec.ElementDefinition
import io.eia.b2bgateway.domain.spec.MessageIdentifier
import io.eia.b2bgateway.domain.spec.SegmentDefinition
import io.eia.b2bgateway.domain.spec.Status
import io.eia.b2bgateway.domain.spec.StructureNode
import io.eia.b2bgateway.domain.spec.SyntaxRules
import io.eia.b2bgateway.domain.validation.InterchangeResult
import io.eia.b2bgateway.domain.validation.PartyIdentification
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.FixedClock
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant

private fun simple(
    id: String,
    format: String,
    status: Status = Status.MANDATORY,
): ElementDefinition.Simple = ElementDefinition.Simple(id, status, checkNotNull(DataFormat.parse(format)))

private fun composite(
    id: String,
    vararg components: ElementDefinition.Simple,
): ElementDefinition.Composite = ElementDefinition.Composite(id, Status.MANDATORY, components.toList())

/** サービスセグメントと BGM だけの仕様(仕様の中身の検査は domain と adapters のテストで行う)。 */
private fun spec(
    type: String,
    release: String,
): EdiSpec =
    EdiSpec(
        name = "test-$type",
        version = 1,
        direction = Direction.INBOUND,
        message = MessageIdentifier(type, "D", release, "UN"),
        syntax = SyntaxRules(setOf(3), setOf(CharacterSet.UNOA), 10),
        segments =
            mapOf(
                "UNB" to
                    SegmentDefinition(
                        "UNB",
                        listOf(
                            composite("S001", simple("0001", "a4"), simple("0002", "n1")),
                            composite("S002", simple("0004", "an..35"), simple("0007", "an..4")),
                            composite("S003", simple("0010", "an..35"), simple("0007", "an..4")),
                            composite("S004", simple("0017", "n6"), simple("0019", "n4")),
                            simple("0020", "an..14"),
                        ),
                    ),
                "UNH" to
                    SegmentDefinition(
                        "UNH",
                        listOf(
                            simple("0062", "an..14"),
                            composite(
                                "S009",
                                simple("0065", "an..6"),
                                simple("0052", "an..3"),
                                simple("0054", "an..3"),
                                simple("0051", "an..2"),
                            ),
                        ),
                    ),
                "UNT" to SegmentDefinition("UNT", listOf(simple("0074", "n..6"), simple("0062", "an..14"))),
                "UNZ" to SegmentDefinition("UNZ", listOf(simple("0036", "n..6"), simple("0020", "an..14"))),
                "BGM" to SegmentDefinition("BGM", listOf(simple("1004", "an..35"))),
            ),
        structure = listOf(StructureNode.SegmentNode("BGM", mandatory = true, max = 1)),
    )

private object Specs : EdiSpecs {
    override val orders: EdiSpec = spec("ORDERS", "96A")
    override val contrl: EdiSpec = spec("CONTRL", "3")
}

private class FakeReferences(
    private val failure: DomainError? = null,
) : ControlReferences {
    val requested = mutableListOf<PartyIdentification>()

    override suspend fun next(recipient: PartyIdentification): Result<String, DomainError> {
        requested += recipient
        return failure?.let { err(it) } ?: ok("R-${requested.size}")
    }
}

private data object StoreDown : DomainError.Retryable {
    override val code: String = "store_down"
    override val message: String = "管理番号を振れません"
}

private val NOW = Instant.parse("2026-10-31T00:00:00Z")
private const val VALID = "UNA:+.? 'UNB+UNOA:3+S:14+R:14+261030:1200+ICR'UNH+1+ORDERS:D:96A:UN'BGM+PO'UNT+3+1'UNZ+1+ICR'"

class CheckInterchangeSpec :
    FunSpec({
        test("受け付けた交換: 送り手あての管理番号を振り、CONTRL(UCI = 7・UCM = 7)を受け取った文字セットで返す") {
            val references = FakeReferences()

            val syntax =
                CheckInterchangeService(Specs, references, FixedClock(NOW))(Latin1.encode(VALID))
                    .shouldBeInstanceOf<Result.Ok<SyntaxCheck>>()
                    .value

            syntax.result
                .shouldBeInstanceOf<InterchangeResult.Checked>()
                .messages
                .single()
                .accepted shouldBe true
            references.requested shouldContainExactly listOf(PartyIdentification("S", "14"))
            Latin1.decode(checkNotNull(syntax.contrl)) shouldBe
                "UNA:+.? 'UNB+UNOA:3+R:14+S:14+261031:0000+R-1'UNH+1+CONTRL:D:3:UN'" +
                "UCI+ICR+S:14+R:14+7'UCM+1+ORDERS:D:96A:UN+7'UNT+4+1'UNZ+1+R-1'"
        }

        test("拒否した交換にも CONTRL を返す(UCI = 4)") {
            val check =
                CheckInterchangeService(Specs, FakeReferences(), FixedClock(NOW))(Latin1.encode(VALID.removePrefix("UNA:+.? '")))
                    .shouldBeInstanceOf<Result.Ok<SyntaxCheck>>()
                    .value

            check.result.shouldBeInstanceOf<InterchangeResult.Rejected>()
            Latin1.decode(checkNotNull(check.contrl)) shouldBe
                "UNA:+.? 'UNB+UNOA:3+R:14+S:14+261031:0000+R-1'UNH+1+CONTRL:D:3:UN'UCI+ICR+S:14+R:14+4+13+UNA'UNT+3+1'UNZ+1+R-1'"
        }

        test("UNB を読めない交換: 管理番号を振らず、CONTRL を返さない") {
            val references = FakeReferences()

            val check =
                CheckInterchangeService(Specs, references, FixedClock(NOW))(Latin1.encode("UNA:+.? 'BGM+PO'"))
                    .shouldBeInstanceOf<Result.Ok<SyntaxCheck>>()
                    .value

            check.result.shouldBeInstanceOf<InterchangeResult.Unreadable>()
            check.contrl shouldBe null
            references.requested.shouldBeEmpty()
        }

        test("管理番号を振れなければ、そのエラーを返す(呼び出し元がやり直す)") {
            CheckInterchangeService(Specs, FakeReferences(StoreDown), FixedClock(NOW))(Latin1.encode(VALID)) shouldBe err(StoreDown)
        }
    })
