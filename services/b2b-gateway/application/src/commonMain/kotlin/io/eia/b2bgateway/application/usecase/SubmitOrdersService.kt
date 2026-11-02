package io.eia.b2bgateway.application.usecase

import io.eia.b2bgateway.application.port.inbound.FailedSubmission
import io.eia.b2bgateway.application.port.inbound.SubmitOrdersUseCase
import io.eia.b2bgateway.application.port.inbound.SubmitSummary
import io.eia.b2bgateway.application.port.outbound.AcknowledgementKind
import io.eia.b2bgateway.application.port.outbound.ControlReferences
import io.eia.b2bgateway.application.port.outbound.DocumentCrypto
import io.eia.b2bgateway.application.port.outbound.EdiSpecs
import io.eia.b2bgateway.application.port.outbound.OrderApi
import io.eia.b2bgateway.application.port.outbound.OrderSubmissions
import io.eia.b2bgateway.application.port.outbound.OriginalArchive
import io.eia.b2bgateway.application.port.outbound.PendingSubmission
import io.eia.b2bgateway.application.port.outbound.Placement
import io.eia.b2bgateway.application.port.outbound.StoredAcknowledgement
import io.eia.b2bgateway.domain.ack.Aperak
import io.eia.b2bgateway.domain.ack.BusinessResponse
import io.eia.b2bgateway.domain.ack.Envelopes
import io.eia.b2bgateway.domain.ack.ResponseEnvelope
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.getOrElse
import io.eia.shared.kernel.ok
import kotlin.time.Clock

/** [SubmitOrdersUseCase] の実装。状態を持たない。 */
public class SubmitOrdersService(
    private val submissions: OrderSubmissions,
    private val orderApi: OrderApi,
    private val acknowledgements: AcknowledgementPorts,
    private val clock: Clock,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) : SubmitOrdersUseCase {
    override suspend fun invoke(): Result<SubmitSummary, DomainError> {
        val pending = submissions.pending(batchSize).getOrElse { return err(it) }
        val accepted = mutableListOf<String>()
        val refused = mutableListOf<String>()
        val expired = mutableListOf<String>()
        val failed = mutableListOf<FailedSubmission>()
        pending.forEach { submission ->
            when (val result = submit(submission)) {
                is Result.Ok -> {
                    when (result.value) {
                        Outcome.ACCEPTED -> accepted += submission.id
                        Outcome.REFUSED -> refused += submission.id
                        Outcome.EXPIRED -> expired += submission.id
                    }
                }

                is Result.Err -> {
                    failed += FailedSubmission(submission.id, result.error)
                    // 記録に失敗しても、ほかの注文は続ける(この注文は送信待ちのまま。次の回に同じキーで送り直す)
                    submissions.failed(submission, result.error.code)
                }
            }
        }
        return ok(SubmitSummary(accepted, refused, expired, failed))
    }

    @Suppress("ReturnCount") // 期限切れと、APERAK の組み立ての失敗のそれぞれで返す
    private suspend fun submit(submission: PendingSubmission): Result<Outcome, DomainError> {
        if (clock.now() >= submission.retryUntil) return submissions.expired(submission).flatMap { ok(Outcome.EXPIRED) }
        return orderApi.place(submission.idempotencyKey, submission.body).flatMap { placement ->
            when (placement) {
                is Placement.Accepted -> {
                    val acknowledgement =
                        if (submission.positiveAperak) aperak(submission, accepted = true).getOrElse { return err(it) } else null
                    submissions.accepted(submission, placement.orderId, acknowledgement).flatMap { ok(Outcome.ACCEPTED) }
                }

                is Placement.Refused -> {
                    val acknowledgement = aperak(submission, accepted = false).getOrElse { return err(it) }
                    submissions.refused(submission, placement.status, acknowledgement).flatMap { ok(Outcome.REFUSED) }
                }
            }
        }
    }

    /**
     * 注文 1 つの APERAK(受け付けは AP、拒否は RE と APR901)を、受け取った交換の UNB の宛先・区切り文字・文字セットで組み立て、
     * 取引先あてに暗号化・署名して原本として保管する(ADR-0041 §5)。
     */
    @Suppress("ReturnCount") // 管理番号・暗号化の失敗のそれぞれで返す
    private suspend fun aperak(
        submission: PendingSubmission,
        accepted: Boolean,
    ): Result<StoredAcknowledgement, DomainError> {
        val specs = acknowledgements.specs
        val header = submission.header
        val errors = if (accepted) emptyList() else listOf(specs.aperak.errorTexts.first { it.code == ORDER_NOT_ACCEPTED })
        val reference = acknowledgements.references.next(header.sender).getOrElse { return err(it) }
        val segments =
            Aperak.build(
                specs.aperak,
                header,
                listOf(BusinessResponse(submission.orderNumber, errors)),
                ResponseEnvelope(reference, MESSAGE_REFERENCE, clock.now()),
            )
        val plain = Envelopes.write(header, segments)
        val sealed = acknowledgements.crypto.seal(submission.partnerId, plain).getOrElse { return err(it) }
        return acknowledgements.archive.storeSent(submission.partnerId, AcknowledgementKind.APERAK, sealed, plain)
    }

    private enum class Outcome { ACCEPTED, REFUSED, EXPIRED }

    private companion object {
        // 社内の受注のシステムが受け付けなかった(contracts/files/edi/edifact-aperak.v1.yaml の errorTexts)
        const val ORDER_NOT_ACCEPTED = "APR901"

        // APERAK の交換には、送信待ちの注文 1 つのメッセージだけを入れる
        const val MESSAGE_REFERENCE = "1"

        // 1 回で送る注文の上限(残りは次の回)
        const val DEFAULT_BATCH_SIZE = 50
    }
}

/** 業務の応答(APERAK)を組み立てて保管するための Port(仕様・応答の管理番号・暗号化と署名・原本の保管)。 */
public class AcknowledgementPorts(
    public val specs: EdiSpecs,
    public val references: ControlReferences,
    public val crypto: DocumentCrypto,
    public val archive: OriginalArchive,
)
