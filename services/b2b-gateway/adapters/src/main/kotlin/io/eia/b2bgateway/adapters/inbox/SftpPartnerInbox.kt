package io.eia.b2bgateway.adapters.inbox

import io.eia.b2bgateway.application.port.inbound.UnknownPartner
import io.eia.b2bgateway.application.port.outbound.PartnerInbox
import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.sftp.RemoteFile
import io.eia.platform.filetransfer.sftp.SftpConfig
import io.eia.platform.filetransfer.sftp.SftpReceiver
import io.eia.platform.security.secret.SecretProvider
import io.eia.platform.tradingpartner.Completion
import io.eia.platform.tradingpartner.TradingPartner
import io.eia.platform.tradingpartner.TradingPartnerLedger
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError
import io.eia.shared.resilience.Resilience
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * 取引先の SFTP の受け取りのディレクトリ(ADR-0040 §4)。台帳の `sftp.directories.inbound` を見に行く(pull)。
 * | ディレクトリ | 置くもの |
 * |---|---|
 * | `{inbound}` | 取引先が置いたファイル |
 * | `{inbound}/processing` | 押さえたファイル(処理が終わるまで) |
 * | `{inbound}/processed` | 処理が終わったファイル(`{受け取りの記録の ID}-{名前}`) |
 *
 * **書き終わりの判断**(台帳の `sftp.completion`。取引先はマニフェストを置かない前提)
 * - `rename`: 取引先は一時名(`temporarySuffix`)で書き、正式名に変える。一時名のファイルは拾わない
 * - `stable`: 前の見回りから大きさと更新の時刻が変わっておらず、更新の時刻から `quietPeriod` が経ったファイルだけを拾う。前の見回りの
 *   観測はメモリに持つ(再起動の後は、最初の見回りでは拾わない)
 *
 * PGP の暗号文は中身を読まないと完全かを判断できない(UNZ は暗号の中)。書きかけの暗号文は、復号の段階で拒否される
 * (`pgp_malformed` など。ADR-0040 §4・§8)ので、TPA では `rename` を勧める。
 *
 * SFTP の呼び出しは、取引先ごとの [Resilience](名前 `sftp-{取引先の ID}`。送信と共有する。app で作る)で包む。
 */
public class SftpPartnerInbox(
    private val ledger: TradingPartnerLedger,
    private val secrets: SecretProvider,
    private val resilience: Map<String, Resilience>,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: Clock = Clock.systemUTC(),
) : PartnerInbox {
    // stable の判断のための、前の見回りの観測(取引先の ID と名前 → 大きさと更新の時刻)
    private val observed = ConcurrentHashMap<Pair<String, String>, RemoteFile>()

    override suspend fun ready(partnerId: String): Result<List<String>, DomainError> =
        call(partnerId) { partner, receiver -> receiver.list(inbound(partner)) }.map { files ->
            val partner = checkNotNull(ledger[partnerId])
            when (val completion = partner.sftp.completion) {
                is Completion.Rename -> files.filterNot { it.name.endsWith(completion.temporarySuffix) }.map { it.name }
                is Completion.Stable -> stable(partnerId, files, completion.quietPeriod)
                null -> emptyList()
            }
        }

    override suspend fun claimed(partnerId: String): Result<List<String>, DomainError> =
        call(partnerId) { partner, receiver -> receiver.list(processing(partner)) }.map { files -> files.map { it.name } }

    override suspend fun claim(
        partnerId: String,
        name: String,
    ): Result<Boolean, DomainError> =
        call(partnerId) { partner, receiver -> receiver.rename(inbound(partner), name, processing(partner)) }.map { claimed ->
            observed.remove(partnerId to name)
            claimed
        }

    override suspend fun read(
        partnerId: String,
        name: String,
    ): Result<ByteArray, DomainError> = call(partnerId) { partner, receiver -> receiver.read(processing(partner), name, maxBytes) }

    override suspend fun finish(
        partnerId: String,
        name: String,
        documentId: String,
    ): Result<Unit, DomainError> =
        call(partnerId) { partner, receiver -> receiver.rename(processing(partner), name, processed(partner), "$documentId-$name") }.map { }

    /** 大きさと更新の時刻が前の見回りと同じで、更新から [quietPeriod] が経ったファイル。今回の観測を残す。 */
    private fun stable(
        partnerId: String,
        files: List<RemoteFile>,
        quietPeriod: Duration,
    ): List<String> {
        val now = clock.instant()
        val names = files.map { it.name }.toSet()
        observed.keys.removeIf { (partner, name) -> partner == partnerId && name !in names }
        return files
            .filter { file ->
                val previous = observed.put(partnerId to file.name, file)
                previous == file && !file.modifiedAt.plus(quietPeriod).isAfter(now)
            }.map { it.name }
    }

    private suspend fun <T> call(
        partnerId: String,
        block: (TradingPartner, SftpReceiver) -> Result<T, FileTransferError>,
    ): Result<T, DomainError> {
        val partner = ledger[partnerId] ?: return err(UnknownPartner(partnerId))
        val sftp = partner.sftp
        val receiver =
            SftpReceiver(
                SftpConfig(sftp.host, sftp.port, sftp.username, sftp.privateKey, sftp.knownHosts, sftp.privateKeyPassphrase),
                secrets,
            )
        return resilience.getValue(partnerId).execute {
            // sshj の呼び出しはブロックするので、割り込みできる IO のスレッドで呼ぶ(試行のタイムアウトで止める。ADR-0032 §8)
            runInterruptible(Dispatchers.IO) { block(partner, receiver).mapError(FileTransferError::asDomainError) }
        }
    }

    private fun inbound(partner: TradingPartner): String =
        checkNotNull(partner.sftp.directories.inbound) { "台帳の検証で、受け取る取引先には受け取りのディレクトリがあります" }

    private fun processing(partner: TradingPartner): String = "${inbound(partner).trimEnd('/')}/$PROCESSING"

    private fun processed(partner: TradingPartner): String = "${inbound(partner).trimEnd('/')}/$PROCESSED"

    public companion object {
        public const val PROCESSING: String = "processing"
        public const val PROCESSED: String = "processed"

        /** 1 つの文書の大きさの上限(B2B の文書は小さい。超えたら読まずに失敗にし、押さえたまま残す)。 */
        public const val DEFAULT_MAX_BYTES: Long = 16L * 1024 * 1024
    }
}
