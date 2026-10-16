package io.eia.fileexchange.adapters.partner

import io.eia.fileexchange.application.port.outbound.Partners
import io.eia.fileexchange.domain.PartnerId
import io.eia.platform.filetransfer.sftp.SftpConfig
import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 取引先の台帳(ADR-0032 §8)。取引先の ID ごとに、送るデータセット・送り先(SFTP)・ホスト鍵・PGP の公開鍵を持つ。
 * P12 の B2B Gateway の取引先の台帳に、この形のまま引き継ぐ。
 *
 * 台帳には秘密情報の**名前**だけを書く(値は [io.eia.platform.security.secret.SecretProvider] から読む。ADR-0019 §6)。
 * ホスト鍵は秘密ではないが、鍵と同じく差し替えるので秘密情報として読む。自社の PGP の署名の鍵は取引先によらないので、台帳には書かない。
 */
public class PartnerRegistry(
    partners: List<TradingPartner>,
) : Partners {
    private val byId: Map<PartnerId, TradingPartner> = partners.associateBy { it.id }

    init {
        require(byId.size == partners.size) { "取引先の ID が重複しています" }
    }

    public val partners: List<TradingPartner> get() = byId.values.sortedBy { it.id.value }

    public operator fun get(id: PartnerId): TradingPartner? = byId[id]

    override fun recipients(dataset: String): List<PartnerId> = partners.filter { dataset in it.datasets }.map { it.id }

    public companion object {
        private val JSON = Json { ignoreUnknownKeys = false }
        private val DATASET = Regex("^[a-z0-9]+_[a-z0-9_]+$")
        private const val MAX_PORT = 65_535

        /** 台帳の JSON を読む。誤りは項目のパスと理由で返す(値は入れない)。 */
        @Suppress("ReturnCount") // JSON として読めないときに返す
        public fun parse(json: String): Result<PartnerRegistry, ValidationError> {
            val file =
                try {
                    JSON.decodeFromString(RegistryJson.serializer(), json)
                } catch (_: SerializationException) {
                    return err(ValidationError.of("partners", "取引先の台帳を JSON として読めません(形・項目の名前を確かめてください)"))
                } catch (_: IllegalArgumentException) {
                    return err(ValidationError.of("partners", "取引先の台帳を JSON として読めません(形・項目の名前を確かめてください)"))
                }
            val violations = mutableListOf<FieldViolation>()
            val partners = file.partners.mapIndexedNotNull { index, entry -> entry.toPartner("partners[$index]", violations) }
            if (partners.map { it.id }.distinct().size != partners.size) violations += FieldViolation("partners", "取引先の ID が重複しています")
            return if (violations.isEmpty()) ok(PartnerRegistry(partners)) else err(ValidationError(violations))
        }

        private fun PartnerJson.toPartner(
            path: String,
            violations: MutableList<FieldViolation>,
        ): TradingPartner? {
            val before = violations.size

            fun expect(
                ok: Boolean,
                field: String,
                reason: String,
            ) {
                if (!ok) violations += FieldViolation("$path.$field", reason)
            }
            val partnerId = orNullIfInvalid { PartnerId(id) }
            expect(partnerId != null, "id", "英小文字・数字・ハイフンの 1〜32 文字にしてください")
            expect(datasets.isNotEmpty(), "datasets", "送るデータセットを 1 つ以上書いてください")
            datasets.forEachIndexed { i, dataset -> expect(DATASET.matches(dataset), "datasets[$i]", "{system}_{dataset} の形にしてください") }
            expect(sftp.host.isNotBlank(), "sftp.host", "空にできません")
            expect(sftp.port in 1..MAX_PORT, "sftp.port", "1〜$MAX_PORT にしてください")
            expect(sftp.username.isNotBlank(), "sftp.username", "空にできません")
            expect(sftp.directory.startsWith("/"), "sftp.directory", "/ で始まる絶対パスにしてください")
            val knownHosts = secretName(sftp.knownHostsSecret, "$path.sftp.knownHostsSecret", violations)
            val privateKey = secretName(sftp.privateKeySecret, "$path.sftp.privateKeySecret", violations)
            val passphrase = sftp.privateKeyPassphraseSecret?.let { secretName(it, "$path.sftp.privateKeyPassphraseSecret", violations) }
            val publicKey = secretName(pgp.publicKeySecret, "$path.pgp.publicKeySecret", violations)
            if (violations.size != before) return null
            return TradingPartner(
                id = checkNotNull(partnerId),
                datasets = datasets.toSet(),
                sftp = SftpConfig(sftp.host, sftp.port, sftp.username, checkNotNull(privateKey), checkNotNull(knownHosts), passphrase),
                directory = sftp.directory,
                pgpPublicKey = checkNotNull(publicKey),
            )
        }

        private fun secretName(
            value: String,
            field: String,
            violations: MutableList<FieldViolation>,
        ): SecretName? =
            orNullIfInvalid { SecretName(value) }.also {
                if (it == null) violations += FieldViolation(field, "秘密情報の名前は [A-Z][A-Z0-9_]* の形にしてください")
            }

        private inline fun <T> orNullIfInvalid(block: () -> T): T? =
            try {
                block()
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}

/**
 * 取引先(台帳の 1 件)。
 *
 * @property sftp 送り先の SFTP(ホスト鍵 `knownHosts`・クライアントの鍵は秘密情報の名前)
 * @property directory 送り先のディレクトリ(絶対パス)
 * @property pgpPublicKey 取引先の PGP の公開鍵(暗号化に使う)の秘密情報の名前
 */
public data class TradingPartner(
    val id: PartnerId,
    val datasets: Set<String>,
    val sftp: SftpConfig,
    val directory: String,
    val pgpPublicKey: SecretName,
)

@Serializable
private class RegistryJson(
    val partners: List<PartnerJson>,
)

@Serializable
private class PartnerJson(
    val id: String,
    val datasets: List<String>,
    val sftp: SftpJson,
    val pgp: PgpJson,
)

@Serializable
@Suppress("LongParameterList") // 台帳の JSON の項目
private class SftpJson(
    val host: String,
    val port: Int = DEFAULT_SSH_PORT,
    val username: String,
    val directory: String,
    val knownHostsSecret: String,
    val privateKeySecret: String,
    val privateKeyPassphraseSecret: String? = null,
)

@Serializable
private class PgpJson(
    val publicKeySecret: String,
)

private const val DEFAULT_SSH_PORT = 22
