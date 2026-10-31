package io.eia.b2bgateway.application.usecase

import io.eia.b2bgateway.application.port.inbound.AcceptDocumentUseCase
import io.eia.b2bgateway.application.port.inbound.AcceptedDocument
import io.eia.b2bgateway.application.port.inbound.FailedFile
import io.eia.b2bgateway.application.port.inbound.IncomingDocument
import io.eia.b2bgateway.application.port.inbound.PollInboxUseCase
import io.eia.b2bgateway.application.port.inbound.PollSummary
import io.eia.b2bgateway.application.port.outbound.PartnerInbox
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.getOrElse
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok

/** [PollInboxUseCase] の実装。状態を持たない(押さえたファイルは取引先の SFTP の処理中のディレクトリにある)。 */
public class PollInboxService(
    private val inbox: PartnerInbox,
    private val accept: AcceptDocumentUseCase,
) : PollInboxUseCase {
    @Suppress("ReturnCount") // 一覧・押さえの失敗のそれぞれで返す
    override suspend fun invoke(partnerId: String): Result<PollSummary, DomainError> {
        val leftovers = inbox.claimed(partnerId).getOrElse { return err(it) }
        val ready = inbox.ready(partnerId).getOrElse { return err(it) }
        val claimed = ready.filter { name -> inbox.claim(partnerId, name).getOrElse { return err(it) } }
        val accepted = mutableListOf<AcceptedDocument>()
        val failed = mutableListOf<FailedFile>()
        (leftovers + claimed).distinct().forEach { name ->
            when (val result = process(partnerId, name)) {
                is Result.Ok -> accepted += result.value
                is Result.Err -> failed += FailedFile(name, result.error)
            }
        }
        return ok(PollSummary(accepted, failed))
    }

    /** 押さえたファイルを読み、受け付けて、処理済みに移す。 */
    private suspend fun process(
        partnerId: String,
        name: String,
    ): Result<AcceptedDocument, DomainError> =
        inbox
            .read(partnerId, name)
            .flatMap { content -> accept(IncomingDocument(partnerId, name, content)) }
            .flatMap { accepted -> inbox.finish(partnerId, name, accepted.documentId).map { accepted } }
}
