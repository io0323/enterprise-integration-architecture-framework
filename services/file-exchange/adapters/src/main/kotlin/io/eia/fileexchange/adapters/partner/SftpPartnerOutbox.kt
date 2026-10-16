package io.eia.fileexchange.adapters.partner

import io.eia.fileexchange.application.port.outbound.PartnerOutbox
import io.eia.fileexchange.domain.PartnerId
import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.LocalFileFailed
import io.eia.platform.filetransfer.ObjectMissing
import io.eia.platform.filetransfer.pgp.PgpFiles
import io.eia.platform.filetransfer.pgp.PgpKeyNames
import io.eia.platform.filetransfer.pgp.PgpKeys
import io.eia.platform.filetransfer.sftp.SftpSender
import io.eia.platform.filetransfer.store.ObjectStore
import io.eia.platform.security.secret.SecretName
import io.eia.platform.security.secret.SecretProvider
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.mapError
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.Resilience
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/**
 * 取引先の送り先(SFTP)。archive のデータファイルを PGP で暗号化・署名して送る(Framework 9.2・12.2。ADR-0032 §8)。
 *
 * - SFTP の呼び出し(確かめる・送る)は、取引先ごとの [Resilience](`sftp-{取引先の ID}`)で Timeout・Retry・Circuit Breaker を付ける
 *   (ADR-0021)。外への同期の呼び出しなので、取引先ごとに状態を分ける(1 つの取引先の不調で、ほかの取引先を止めない)。
 *   Fallback は持たない: 送れなければ配送を「送信中」のまま残してステップを失敗にし、再実行で続ける
 * - archive の読み出しと暗号化は Resilience の外で行う(置き場の不調を、取引先の Circuit Breaker に数えない)
 * - sshj の呼び出しはブロックするので、割り込みできる IO のスレッドで呼ぶ(試行のタイムアウトで止める)
 *
 * @param ownPgpKey 自社の署名の鍵(取引先によらない)
 * @param resilience 取引先ごとの Resilience(app で `ResilienceMetrics` から作る)。台帳の取引先すべての分が要る
 *
 * 一時ファイル(平文と暗号文)はシステムの一時ディレクトリに作り、送り終えたら(失敗しても)消す。
 */
public class SftpPartnerOutbox(
    private val registry: PartnerRegistry,
    private val store: ObjectStore,
    private val archivePrefix: String,
    private val ownPgpKey: OwnPgpKey,
    private val secrets: SecretProvider,
    private val resilience: Map<PartnerId, Resilience>,
) : PartnerOutbox {
    init {
        require(registry.partners.all { it.id in resilience }) { "台帳のすべての取引先の Resilience を渡してください" }
    }

    override suspend fun exists(
        partner: PartnerId,
        remoteName: String,
    ): Result<Boolean, DomainError> {
        val target = registry[partner] ?: return unknown(partner)
        val sender = SftpSender(target.sftp, secrets)
        return resilience.getValue(partner).execute {
            runInterruptible(Dispatchers.IO) { sender.exists(target.directory, remoteName).domain() }
        }
    }

    override suspend fun send(
        partner: PartnerId,
        name: String,
        dataFile: String,
        remoteName: String,
    ): Result<Unit, DomainError> {
        val target = registry[partner] ?: return unknown(partner)
        return withTempFiles { plain, encrypted ->
            when (val prepared = runInterruptible(Dispatchers.IO) { prepare(target, dataFile, plain, encrypted) }) {
                is Result.Err -> {
                    prepared
                }

                is Result.Ok -> {
                    val sender = SftpSender(target.sftp, secrets)
                    resilience.getValue(partner).execute {
                        runInterruptible(Dispatchers.IO) { sender.send(encrypted, target.directory, remoteName).domain() }
                    }
                }
            }
        }
    }

    /** 平文と暗号文の一時ファイルを作って [block] に渡し、終わったら(失敗しても)消す。 */
    private suspend fun withTempFiles(block: suspend (Path, Path) -> Result<Unit, DomainError>): Result<Unit, DomainError> {
        val files = mutableListOf<Path>()
        return try {
            files.add(Files.createTempFile("deliver-", ".plain"))
            files.add(Files.createTempFile("deliver-", ".pgp"))
            block(files[0], files[1])
        } catch (e: IOException) {
            err(LocalFileFailed(e::class.simpleName ?: "IOException"))
        } finally {
            files.forEach { it.deleteIfExists() }
        }
    }

    /** archive のデータファイルを読み出し、取引先の公開鍵で暗号化して自社の鍵で署名する。 */
    @Suppress("ReturnCount") // 読み出し・鍵・暗号化の失敗のそれぞれで返す
    private fun prepare(
        target: TradingPartner,
        dataFile: String,
        plain: Path,
        encrypted: Path,
    ): Result<Unit, DomainError> {
        val key = archivePrefix + dataFile
        when (val downloaded = store.download(key, plain)) {
            is Result.Err -> return downloaded.domain()
            is Result.Ok -> if (!downloaded.value) return err(ObjectMissing(key))
        }
        val keys =
            when (val loaded = PgpKeys.load(secrets, PgpKeyNames(target.pgpPublicKey, ownPgpKey.secretKey, ownPgpKey.passphrase))) {
                is Result.Err -> return loaded.domain()
                is Result.Ok -> loaded.value
            }
        return PgpFiles.encryptAndSign(plain, encrypted, keys, dataFile).domain()
    }

    private fun <T> unknown(partner: PartnerId): Result<T, DomainError> = err(UnexpectedError("台帳にない取引先です($partner)"))

    private fun <T> Result<T, FileTransferError>.domain(): Result<T, DomainError> = mapError { it.asDomainError() }
}

/** 自社の PGP の署名の鍵(秘密鍵と、そのパスフレーズの秘密情報の名前)。取引先によらず 1 つ。 */
public data class OwnPgpKey(
    val secretKey: SecretName,
    val passphrase: SecretName,
)
