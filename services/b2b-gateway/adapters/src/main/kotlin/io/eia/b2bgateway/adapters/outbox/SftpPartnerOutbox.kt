package io.eia.b2bgateway.adapters.outbox

import io.eia.b2bgateway.application.port.inbound.UnknownPartner
import io.eia.b2bgateway.application.port.outbound.Delivery
import io.eia.b2bgateway.application.port.outbound.Destination
import io.eia.b2bgateway.application.port.outbound.DocumentCrypto
import io.eia.b2bgateway.application.port.outbound.OutboundDocument
import io.eia.b2bgateway.application.port.outbound.PartnerOutbox
import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.sftp.SftpConfig
import io.eia.platform.filetransfer.sftp.SftpSender
import io.eia.platform.security.secret.SecretProvider
import io.eia.platform.tradingpartner.TradingPartner
import io.eia.platform.tradingpartner.TradingPartnerLedger
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.getOrElse
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.Resilience
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/**
 * 取引先への送信の仕組み(ADR-0040 §7)。台帳の取引先の SFTP の、送り先のディレクトリ(`sftp.directories` の `outbound`・`acknowledgements`)に送る。
 * - 暗号化と署名が済んでいない文書は、[DocumentCrypto.seal] で取引先あてに暗号化し、自社の鍵で署名してから送る
 * - 送る前に、取引先の側に同じ正式名のファイルがあるかを確かめ、あれば送らない([Delivery.ALREADY_PRESENT])。送るときは一時名で書いてから
 *   正式名に変える([SftpSender])
 * - SFTP の呼び出しは、取引先ごとの [Resilience](名前 `sftp-{取引先の ID}`。受け取りと共有する。app で作る)で包む。確認と送信を 1 回の試行にし、
 *   やり直しでは確認からやり直す(前の試行が正式名に変えた後で落ちていれば、送り直さない)
 */
public class SftpPartnerOutbox(
    private val ledger: TradingPartnerLedger,
    private val secrets: SecretProvider,
    private val resilience: Map<String, Resilience>,
    private val crypto: DocumentCrypto,
    private val workDir: Path,
) : PartnerOutbox {
    // 取引先・送り先・暗号化の失敗は、それぞれ送る前に返す
    @Suppress("ReturnCount")
    override suspend fun deliver(
        partnerId: String,
        document: OutboundDocument,
    ): Result<Delivery, DomainError> {
        val partner = ledger[partnerId] ?: return err(UnknownPartner(partnerId))
        val directory =
            directory(partner, document.destination)
                ?: return err(UnexpectedError("台帳の取引先 $partnerId に、送り先(${document.destination.name.lowercase()})のディレクトリがありません"))
        val content =
            if (document.sealed) document.content else crypto.seal(partnerId, document.content).getOrElse { return err(it) }
        val sftp = partner.sftp
        val sender =
            SftpSender(
                SftpConfig(sftp.host, sftp.port, sftp.username, sftp.privateKey, sftp.knownHosts, sftp.privateKeyPassphrase),
                secrets,
            )
        return resilience.getValue(partnerId).execute {
            // sshj の呼び出しはブロックするので、割り込みできる IO のスレッドで呼ぶ(試行のタイムアウトで止める。ADR-0032 §8)
            runInterruptible(Dispatchers.IO) {
                send(sender, directory, document.remoteName, content).mapError(FileTransferError::asDomainError)
            }
        }
    }

    private fun send(
        sender: SftpSender,
        directory: String,
        remoteName: String,
        content: ByteArray,
    ): Result<Delivery, FileTransferError> =
        sender.exists(directory, remoteName).flatMap { present ->
            if (present) {
                ok(Delivery.ALREADY_PRESENT)
            } else {
                val source = Files.createTempFile(workDir, "b2b-send", ".bin")
                try {
                    Files.write(source, content)
                    sender.send(source, directory, remoteName).map { Delivery.SENT }
                } finally {
                    source.deleteIfExists()
                }
            }
        }

    private fun directory(
        partner: TradingPartner,
        destination: Destination,
    ): String? =
        when (destination) {
            Destination.OUTBOUND -> partner.sftp.directories.outbound
            Destination.ACKNOWLEDGEMENTS -> partner.sftp.directories.acknowledgements
        }
}
