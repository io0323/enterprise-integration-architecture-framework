package io.eia.b2bgateway.application.usecase

import io.eia.b2bgateway.application.port.inbound.OrderDecision
import io.eia.b2bgateway.application.port.inbound.Receipt
import io.eia.b2bgateway.application.port.inbound.ReceiveInterchangeUseCase
import io.eia.b2bgateway.application.port.inbound.ReceivedDocument
import io.eia.b2bgateway.application.port.inbound.UnknownPartner
import io.eia.b2bgateway.application.port.outbound.CodeLookup
import io.eia.b2bgateway.application.port.outbound.CodeMappings
import io.eia.b2bgateway.application.port.outbound.ControlReferences
import io.eia.b2bgateway.application.port.outbound.EdiSpecs
import io.eia.b2bgateway.application.port.outbound.PartnerAgreements
import io.eia.b2bgateway.application.port.outbound.ReceivedInterchanges
import io.eia.b2bgateway.application.port.outbound.ReceivedOrders
import io.eia.b2bgateway.domain.ack.Aperak
import io.eia.b2bgateway.domain.ack.BusinessResponse
import io.eia.b2bgateway.domain.ack.Contrl
import io.eia.b2bgateway.domain.ack.Envelopes
import io.eia.b2bgateway.domain.ack.ResponseEnvelope
import io.eia.b2bgateway.domain.agreement.PartnerAgreement
import io.eia.b2bgateway.domain.edifact.EdifactLexer
import io.eia.b2bgateway.domain.edifact.Latin1
import io.eia.b2bgateway.domain.edifact.Segment
import io.eia.b2bgateway.domain.order.BusinessContext
import io.eia.b2bgateway.domain.order.OrderRules
import io.eia.b2bgateway.domain.order.PurchaseOrders
import io.eia.b2bgateway.domain.receipt.DuplicateCheck
import io.eia.b2bgateway.domain.receipt.Duplicates
import io.eia.b2bgateway.domain.receipt.InterchangeKey
import io.eia.b2bgateway.domain.validation.InterchangeCheck
import io.eia.b2bgateway.domain.validation.InterchangeHeader
import io.eia.b2bgateway.domain.validation.InterchangeResult
import io.eia.b2bgateway.domain.validation.ServiceError
import io.eia.b2bgateway.domain.validation.SyntaxErrorCode
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.getOrElse
import io.eia.shared.kernel.ok
import kotlin.time.Clock

/**
 * [ReceiveInterchangeUseCase] の実装。状態を持たない(記録は Port の先)。
 * 順番: 構文とスキーマの段階 → 宛先がわからなければ終わり → 重複の判定(送り直しなら前の応答を返し直して終わり)→ 交換の拒否の理由
 * (管理番号の使い回し・構文・送り手・受け手の順に最初の 1 つ)→ 業務の段階 → 応答の組み立て。
 */
public class ReceiveInterchangeService(
    private val specs: EdiSpecs,
    ports: ReceivingPorts,
    private val clock: Clock,
) : ReceiveInterchangeUseCase {
    private val partners = ports.partners
    private val interchanges = ports.interchanges
    private val mappings = ports.mappings
    private val orders = ports.orders
    private val references = ports.references

    @Suppress("ReturnCount") // 宛先がわからない・送り直し・Port の失敗のそれぞれで返す
    override suspend fun invoke(document: ReceivedDocument): Result<Receipt, DomainError> {
        val agreement = partners.find(document.partnerId).getOrElse { return err(it) } ?: return err(UnknownPartner(document.partnerId))
        val lexed = EdifactLexer.read(Latin1.decode(document.content))
        val syntax = InterchangeCheck.check(lexed, specs.orders)
        val header = syntax.header() ?: return ok(Receipt(agreement, syntax, DuplicateCheck.First, emptyList(), null, null))
        val key = InterchangeKey(agreement.partnerId, header.sender, header.recipient, header.controlReference)
        val duplicate = Duplicates.decide(interchanges.find(key).getOrElse { return err(it) }, document.sha256)
        if (duplicate is DuplicateCheck.Resend) {
            // 前に返した応答は、受け取りの記録の側(adapters)が前の記録から送り直す(新しい応答は作らない)
            return ok(Receipt(agreement, syntax, duplicate, emptyList(), null, null))
        }
        val result = envelopeResult(syntax, header, agreement, duplicate)
        val decisions =
            if (result is InterchangeResult.Checked) {
                decide(result, agreement, lexed.serviceCharacters.decimal).getOrElse { return err(it) }
            } else {
                emptyList()
            }
        val contrl = respond(header) { Contrl.build(specs.contrl, result, it) }.getOrElse { return err(it) }
        val responses = businessResponses(decisions, header.test && agreement.positiveAperak)
        val aperak =
            if (responses.isEmpty()) {
                null
            } else {
                respond(
                    header,
                ) { Aperak.build(specs.aperak, header, responses, it) }.getOrElse { return err(it) }
            }
        return ok(Receipt(agreement, result, duplicate, decisions, contrl, aperak))
    }

    /** 応答の宛先(UNB を読めなければ null)。 */
    private fun InterchangeResult.header(): InterchangeHeader? =
        when (this) {
            is InterchangeResult.Unreadable -> null
            is InterchangeResult.Rejected -> header
            is InterchangeResult.Checked -> header
        }

    /** 交換を拒否する理由(管理番号の使い回し → 構文 → 送り手 → 受け手の順に最初の 1 つ)。なければ構文とスキーマの段階の結果のまま。 */
    @Suppress("ReturnCount") // 構文の誤りと、拒否の理由がないときは、構文とスキーマの段階の結果をそのまま返す
    private fun envelopeResult(
        syntax: InterchangeResult,
        header: InterchangeHeader,
        agreement: PartnerAgreement,
        duplicate: DuplicateCheck,
    ): InterchangeResult {
        val error =
            when {
                duplicate is DuplicateCheck.Reused -> {
                    ServiceError(SyntaxErrorCode.DUPLICATE_DETECTED, UNB, UNB_CONTROL_REFERENCE)
                }

                syntax is InterchangeResult.Rejected -> {
                    return syntax
                }

                header.sender != agreement.sender -> {
                    ServiceError(SyntaxErrorCode.UNKNOWN_INTERCHANGE_SENDER, UNB, UNB_SENDER, 1)
                }

                header.recipient != agreement.recipient -> {
                    ServiceError(
                        SyntaxErrorCode.RECIPIENT_NOT_ACTUAL_RECIPIENT,
                        UNB,
                        UNB_RECIPIENT,
                        1,
                    )
                }

                else -> {
                    return syntax
                }
            }
        return InterchangeResult.Rejected(header, error)
    }

    /** スキーマの段階を通ったメッセージの注文を、業務の段階で検査する(対応表はまとめて 1 回引く)。 */
    @Suppress("ReturnCount") // 注文がない・Port の失敗のそれぞれで返す
    private suspend fun decide(
        result: InterchangeResult.Checked,
        agreement: PartnerAgreement,
        decimal: Char,
    ): Result<List<OrderDecision>, DomainError> {
        val accepted = result.messages.filter { it.accepted }.map { it.reference to PurchaseOrders.extract(it.segments, decimal) }
        if (accepted.isEmpty()) return ok(emptyList())
        val lookup =
            CodeLookup(
                buyers = accepted.map { it.second.buyer }.toSet(),
                deliveryParties = accepted.mapNotNull { it.second.deliveryParty }.toSet(),
                items = accepted.flatMap { (_, order) -> order.lines.map { it.itemCode } }.toSet(),
            )
        val resolution = mappings.resolve(agreement.partnerId, lookup).getOrElse { return err(it) }
        val seen = mutableSetOf<String>()
        return ok(
            accepted.map { (reference, order) ->
                val received =
                    orders.exists(agreement.partnerId, order.orderNumber).getOrElse { return err(it) } || !seen.add(order.orderNumber)
                OrderDecision(reference, order, OrderRules.check(order, BusinessContext(agreement, resolution, received)), resolution)
            },
        )
    }

    /** 業務の段階の応答。拒否は必ず、受け付けは [includeAccepted] のとき(試験の交換で、取引先が肯定の応答を選んでいる)だけ。 */
    private fun businessResponses(
        decisions: List<OrderDecision>,
        includeAccepted: Boolean,
    ): List<BusinessResponse> {
        val texts = (specs.orders.businessRules + specs.aperak.errorTexts).associateBy { it.code }
        return decisions
            .filter { !it.accepted || includeAccepted }
            .map { decision -> BusinessResponse(decision.order.orderNumber, decision.violations.map { texts.getValue(it.code) }) }
    }

    /** 応答の管理番号を振り、セグメントを組み立てて、受け取った交換の文字セットのバイト列にする。 */
    private suspend fun respond(
        header: InterchangeHeader,
        build: (ResponseEnvelope) -> List<Segment>?,
    ): Result<ByteArray?, DomainError> {
        val reference = references.next(header.sender).getOrElse { return err(it) }
        return ok(build(ResponseEnvelope(reference, MESSAGE_REFERENCE, clock.now()))?.let { Envelopes.write(header, it) })
    }

    private companion object {
        // CONTRL の交換にはメッセージが 1 つだけなので、メッセージの参照番号は固定でよい(交換の管理番号で区別する)
        const val MESSAGE_REFERENCE = "1"
        const val UNB = "UNB"
        const val UNB_SENDER = 2
        const val UNB_RECIPIENT = 3
        const val UNB_CONTROL_REFERENCE = 5
    }
}

/** 受け取りで使う Port(取引先の台帳・受け取りの記録・対応表・受け付けた注文・応答の管理番号)。 */
public class ReceivingPorts(
    public val partners: PartnerAgreements,
    public val interchanges: ReceivedInterchanges,
    public val mappings: CodeMappings,
    public val orders: ReceivedOrders,
    public val references: ControlReferences,
)
