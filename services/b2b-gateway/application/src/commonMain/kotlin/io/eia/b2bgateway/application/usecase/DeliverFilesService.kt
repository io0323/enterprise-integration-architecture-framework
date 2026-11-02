package io.eia.b2bgateway.application.usecase

import io.eia.b2bgateway.application.port.inbound.DeliverFilesUseCase
import io.eia.b2bgateway.application.port.inbound.FailedFileDelivery
import io.eia.b2bgateway.application.port.inbound.FileDeliverySummary
import io.eia.b2bgateway.application.port.inbound.HandoffMismatch
import io.eia.b2bgateway.application.port.outbound.Delivery
import io.eia.b2bgateway.application.port.outbound.Destination
import io.eia.b2bgateway.application.port.outbound.FileDeliveries
import io.eia.b2bgateway.application.port.outbound.FileDelivery
import io.eia.b2bgateway.application.port.outbound.FileRecipients
import io.eia.b2bgateway.application.port.outbound.HandoffFile
import io.eia.b2bgateway.application.port.outbound.HandoffFiles
import io.eia.b2bgateway.application.port.outbound.OutboundDocument
import io.eia.b2bgateway.application.port.outbound.PartnerOutbox
import io.eia.b2bgateway.domain.outbound.OutboundFiles
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.getOrElse
import io.eia.shared.kernel.ok
import kotlin.time.Clock
import kotlin.time.Duration

/** [DeliverFilesUseCase] の実装。状態を持たない(送信の記録は Port の先)。 */
public class DeliverFilesService(
    private val ports: FileDeliveryPorts,
    private val sha256: (ByteArray) -> String,
    private val clock: Clock,
    private val retention: Duration,
) : DeliverFilesUseCase {
    init {
        require(retention.isPositive()) { "受け渡しの場所の保存期間は正の値にしてください" }
    }

    override suspend fun invoke(): Result<FileDeliverySummary, DomainError> {
        val listing = ports.handoff.list().getOrElse { return err(it) }
        val summary = SummaryBuilder()
        listing.files.forEach { file -> process(file, summary) }
        return ok(FileDeliverySummary(summary.sent, summary.failed, summary.deleted, listing.invalid))
    }

    /** 1 つのファイル: 記録のない取引先に送る。送る先が残っていなければ、保存期間を過ぎたものを消す。 */
    @Suppress("ReturnCount") // 記録・削除・読み出しの失敗と、送る先がないときのそれぞれで返す
    private suspend fun process(
        file: HandoffFile,
        summary: SummaryBuilder,
    ) {
        val recipients = OutboundFiles.dataset(file.name)?.let(ports.recipients::of).orEmpty()
        val delivered = ports.deliveries.delivered(file.name).getOrElse { return summary.fail(file, null, it) }
        val pending = recipients - delivered
        if (pending.isEmpty()) {
            if (expired(file)) {
                ports.handoff.delete(file).getOrElse { return summary.fail(file, null, it) }
                summary.deleted += file.name
            }
            return
        }
        val content = verified(file).getOrElse { return summary.fail(file, null, it) }
        pending.forEach { partnerId ->
            when (val result = send(file, partnerId, content)) {
                is Result.Ok -> summary.sent += file.name to partnerId
                is Result.Err -> summary.fail(file, partnerId, result.error)
            }
        }
    }

    private class SummaryBuilder {
        val sent = mutableListOf<Pair<String, String>>()
        val failed = mutableListOf<FailedFileDelivery>()
        val deleted = mutableListOf<String>()

        fun fail(
            file: HandoffFile,
            partnerId: String?,
            error: DomainError,
        ) {
            failed += FailedFileDelivery(file.name, partnerId, error)
        }
    }

    /** データファイルを読み、SHA-256 をマニフェストと照らし合わせる。 */
    private suspend fun verified(file: HandoffFile): Result<ByteArray, DomainError> =
        ports.handoff.read(file).flatMap { content -> if (sha256(content) == file.sha256) ok(content) else err(HandoffMismatch(file.name)) }

    private suspend fun send(
        file: HandoffFile,
        partnerId: String,
        content: ByteArray,
    ): Result<Unit, DomainError> {
        val remoteName = OutboundFiles.remoteName(file.dataFile)
        return ports.outbox
            .deliver(partnerId, OutboundDocument(Destination.OUTBOUND, remoteName, content, sealed = false))
            .flatMap { delivery ->
                ports.deliveries.record(FileDelivery(file.name, partnerId, remoteName, file.sha256, delivery == Delivery.ALREADY_PRESENT))
            }
    }

    private fun expired(file: HandoffFile): Boolean = file.createdAt + retention <= clock.now()
}

/** ファイルの送信で使う Port(受け渡しの場所・台帳の受け取る取引先・送信の記録・取引先の送信の仕組み)。 */
public class FileDeliveryPorts(
    public val handoff: HandoffFiles,
    public val recipients: FileRecipients,
    public val deliveries: FileDeliveries,
    public val outbox: PartnerOutbox,
)
