package io.eia.b2bgateway.adapters.crypto

import io.eia.b2bgateway.adapters.archive.sha256
import io.eia.b2bgateway.adapters.archive.withTempFiles
import io.eia.b2bgateway.application.port.inbound.UnknownPartner
import io.eia.b2bgateway.application.port.outbound.DocumentCrypto
import io.eia.b2bgateway.application.port.outbound.Opening
import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.pgp.PgpFiles
import io.eia.platform.filetransfer.pgp.PgpKeyNames
import io.eia.platform.filetransfer.pgp.PgpKeys
import io.eia.platform.filetransfer.pgp.PgpVerification
import io.eia.platform.security.secret.SecretName
import io.eia.platform.security.secret.SecretProvider
import io.eia.platform.tradingpartner.TradingPartnerLedger
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/**
 * B2B の文書の PGP(Framework 9.2。ADR-0040 §8)。取引先の公開鍵は台帳([TradingPartnerLedger])の秘密情報の名前から、自社の鍵は
 * [ownSecretKey]・[ownPassphrase] から読む(鍵は呼び出しのたびに読み直す。差し替えに追従する)。
 * - 受け取った文書: 自社の鍵で復号し、取引先の今の鍵と、差し替えの間の旧い鍵(台帳の acceptUntil を過ぎていないもの)で署名を確かめる
 * - 送る応答: 取引先の今の鍵で暗号化し、自社の鍵で署名する
 * 鍵を読めない・形式の誤りは、取引先の誤りではなく設定の誤り(Result のエラー)にする。文書の誤りは [Opening.Refused]。
 */
public class PgpDocumentCrypto(
    private val ledger: TradingPartnerLedger,
    private val secrets: SecretProvider,
    private val ownSecretKey: SecretName,
    private val ownPassphrase: SecretName,
    private val workDir: Path,
    private val clock: Clock = Clock.systemUTC(),
) : DocumentCrypto {
    override suspend fun open(
        partnerId: String,
        content: ByteArray,
    ): Result<Opening, DomainError> =
        keys(partnerId, withPrevious = true).flatMap { keys ->
            withContext(Dispatchers.IO) {
                withTempFiles(workDir, content) { source, target ->
                    PgpFiles
                        .decryptAndVerify(source, target, keys)
                        .map { verification ->
                            when (verification) {
                                is PgpVerification.Rejected -> {
                                    Opening.Refused(verification.reason.code)
                                }

                                is PgpVerification.Verified -> {
                                    val plain = Files.readAllBytes(target)
                                    Opening.Opened(plain, sha256(plain), verification.signerFingerprint)
                                }
                            }
                        }.mapError(FileTransferError::asDomainError)
                }
            }
        }

    override suspend fun seal(
        partnerId: String,
        plain: ByteArray,
    ): Result<ByteArray, DomainError> =
        keys(partnerId, withPrevious = false).flatMap { keys ->
            withContext(Dispatchers.IO) {
                withTempFiles(workDir, plain) { source, target ->
                    PgpFiles
                        .encryptAndSign(source, target, keys, LITERAL_NAME)
                        .map { Files.readAllBytes(target) }
                        .mapError(FileTransferError::asDomainError)
                }
            }
        }

    private fun keys(
        partnerId: String,
        withPrevious: Boolean,
    ): Result<PgpKeys, DomainError> {
        val pgp = ledger[partnerId]?.pgp ?: return err(UnknownPartner(partnerId))
        val previous = if (withPrevious) pgp.acceptedPrevious(clock.instant()) else emptyList()
        return PgpKeys
            .load(
                secrets,
                PgpKeyNames(pgp.publicKey, ownSecretKey, ownPassphrase, previous),
            ).mapError(FileTransferError::asDomainError)
    }

    private companion object {
        // 暗号文の中のファイル名(受け手は信用しない。参考の名前)
        const val LITERAL_NAME = "acknowledgement.edi"
    }
}
