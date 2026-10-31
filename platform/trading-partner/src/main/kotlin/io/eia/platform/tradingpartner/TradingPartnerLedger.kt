package io.eia.platform.tradingpartner

import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * 取引先の台帳(TPA。Framework 11.1。ADR-0040)。台帳は 1 つのファイル(ローカル基盤は infra/local/b2b/partners.json)で、
 * 読み込みと検証はこのクラスだけが行う(同じ取引先の情報を 2 か所に持たない)。秘密情報は名前だけを持つ。
 */
public class TradingPartnerLedger(
    public val company: Company,
    partners: List<TradingPartner>,
) {
    private val byId: Map<String, TradingPartner> = partners.associateBy { it.id }

    init {
        require(byId.size == partners.size) { "取引先の ID が重複しています" }
    }

    public val partners: List<TradingPartner> get() = byId.values.sortedBy { it.id }

    public operator fun get(id: String): TradingPartner? = byId[id]

    /** 連携([integration])の文書を、その向きで扱う取引先(ファイルの連携は [dataset] も一致するもの)。 */
    public fun partnersFor(
        integration: String,
        direction: Direction,
        dataset: String? = null,
    ): List<TradingPartner> =
        partners.filter { partner ->
            partner.documents.any {
                it.integration == integration && it.direction == direction && (dataset == null || it.dataset == dataset)
            }
        }

    public companion object {
        private const val SCHEMA = "/trading-partner/ledger.v1.schema.json"
        private val json: JsonMapper = JsonMapper.builder().build()

        // メッセージの言語を実行環境のロケールに依存させない(contract-check と同じ)
        private val schema: Schema by lazy {
            val registry =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
                    builder.schemaRegistryConfig(SchemaRegistryConfig.builder().locale(Locale.JAPANESE).build())
                }
            val text = checkNotNull(TradingPartnerLedger::class.java.getResource(SCHEMA)) { "$SCHEMA がリソースにありません" }.readText()
            registry.getSchema(json.readTree(text))
        }

        /**
         * 台帳の JSON を読む。誤りは項目の場所と理由で返す(値は入れない)。
         * 形(JSON Schema)→ 値の解釈(日付・期間・タイムゾーン)→ 取引先の重複と、扱う文書ごとに要る設定、の順に検査する。
         */
        @Suppress("ReturnCount") // 検査の段ごとに、最初の段の誤りで止める(後の段は前の段の形を前提にする)
        public fun parse(text: String): Result<TradingPartnerLedger, ValidationError> {
            val tree =
                try {
                    json.readTree(text)
                } catch (_: JacksonException) {
                    return err(ValidationError.of("partners", "取引先の台帳を JSON として読めません"))
                }
            val schemaErrors = schema.validate(tree).map { FieldViolation(it.instanceLocation.toString(), it.message) }
            if (schemaErrors.isNotEmpty()) return err(ValidationError(schemaErrors))
            val violations = mutableListOf<FieldViolation>()
            val company = company(tree.get("company"))
            val partners =
                tree.get("partners").values().mapIndexedNotNull {
                    index,
                    node,
                    ->
                    LedgerReader("/partners/$index", violations).partner(node)
                }
            partners.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach {
                violations +=
                    FieldViolation("/partners", "取引先の ID '$it' が重複しています")
            }
            return if (violations.isEmpty()) ok(TradingPartnerLedger(company, partners)) else err(ValidationError(violations))
        }

        private fun company(node: JsonNode): Company = Company(node.text("name"), identification(node.get("edifact")), node.text("gln"))
    }
}

/** 自社の識別子。 */
public data class Company(
    val name: String,
    val edifact: PartyIdentification,
    val gln: String,
)

/** UN/EDIFACT の UNB の識別子(識別子と識別の機関)。 */
public data class PartyIdentification(
    val id: String,
    val qualifier: String,
)

public enum class Direction { INBOUND, OUTBOUND }

/** 扱う文書(連携カタログの ID と向き。ファイルの連携はデータセット)。 */
public data class DocumentAgreement(
    val integration: String,
    val direction: Direction,
    val dataset: String? = null,
)

/**
 * 取引先(台帳の 1 件)。
 *
 * @property edifact 取引先が送る交換の UNB の送り手(EDI の文書を受け取る取引先では必ずある)
 * @property pgp 取引先の PGP の公開鍵(ファイルを送る取引先では必ずある)
 */
public data class TradingPartner(
    val id: String,
    val name: String,
    val edifact: PartyIdentification?,
    val documents: List<DocumentAgreement>,
    val sftp: SftpEndpoint,
    val pgp: PgpPublicKey?,
    val agreement: ServiceAgreement,
    val contacts: List<Contact>,
)

/** 取引先の SFTP。鍵とホスト鍵の [CredentialKind] ごとの有効期限([validUntil] は TPA で合意した差し替えの期日)を持つ。 */
public data class SftpEndpoint(
    val host: String,
    val port: Int,
    val username: String,
    val knownHosts: SecretName,
    val hostKeyValidUntil: LocalDate,
    val privateKey: SecretName,
    val privateKeyPassphrase: SecretName?,
    val clientKeyValidUntil: LocalDate,
    val directories: SftpDirectories,
    val completion: Completion?,
)

/** 取引先の SFTP のディレクトリ(絶対パス)。 */
public data class SftpDirectories(
    val outbound: String? = null,
    val inbound: String? = null,
    val acknowledgements: String? = null,
)

/** 取引先が置いたファイルが書き終わったかの判断(ADR-0040 §4)。 */
public sealed interface Completion {
    /** 書いている間は [temporarySuffix] の名前で、書き終わったら正式名に変える(既定) */
    public data class Rename(
        val temporarySuffix: String,
    ) : Completion

    /** 大きさと更新の時刻が [quietPeriod] の間変わらなければ書き終わったとみなす */
    public data class Stable(
        val quietPeriod: Duration,
    ) : Completion
}

/**
 * 取引先の PGP の公開鍵。[previous] は、取引先が署名の鍵を差し替える間だけ署名を受け付ける旧い鍵(暗号化には使わない。ADR-0040 §8)。
 */
public data class PgpPublicKey(
    val publicKey: SecretName,
    val validUntil: LocalDate,
    val previous: List<PreviousPublicKey> = emptyList(),
) {
    /** [now] の時点で、まだ署名を受け付ける旧い鍵。 */
    public fun acceptedPrevious(now: Instant): List<SecretName> = previous.filter { now < it.acceptedUntil }.map { it.publicKey }
}

/** 署名の鍵の差し替えの間の旧い公開鍵。[acceptUntil] の日の終わり(UTC)まで受け付ける。 */
public data class PreviousPublicKey(
    val publicKey: SecretName,
    val acceptUntil: LocalDate,
) {
    public val acceptedUntil: Instant get() = acceptUntil.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
}

/**
 * 取引先との合意(TPA)。
 *
 * @property positiveAperak 業務の受け付けに成功したときにも APERAK(AP)を返すか(P12 の承認の条件 1)
 * @property aperakDeadline 受け取ってから業務の応答(APERAK)を返すまでの期限(承認の条件 1)
 * @property cutoff その日の受注として扱う締め時刻と、そのタイムゾーン
 * @property syntaxAcknowledgementSla 書き終わったファイルを見つけてから CONTRL を返すまで
 */
public data class ServiceAgreement(
    val currencies: Set<String>,
    val positiveAperak: Boolean,
    val aperakDeadline: Duration,
    val cutoff: LocalTime,
    val cutoffZone: ZoneId,
    val syntaxAcknowledgementSla: Duration,
    val resend: String,
)

public enum class ContactRole { BUSINESS, OPERATIONS, INCIDENT }

/** 連絡先(個人ではなく役割の窓口)。 */
public data class Contact(
    val role: ContactRole,
    val name: String,
    val email: String,
    val phone: String?,
)

/** スキーマに適合した木から、取引先を作る。値の解釈と、扱う文書ごとに要る設定の誤りは [violations] に足す。 */
private class LedgerReader(
    private val path: String,
    private val violations: MutableList<FieldViolation>,
) {
    fun partner(node: JsonNode): TradingPartner? {
        val before = violations.size
        val documents =
            node.get("documents").values().map {
                DocumentAgreement(
                    it.text("integration"),
                    Direction.valueOf(it.text("direction").uppercase()),
                    it.get("dataset")?.asString(),
                )
            }
        val sftp = sftp(node.get("sftp"))
        val agreement = agreement(node.get("agreement"))
        val partner =
            TradingPartner(
                id = node.text("id"),
                name = node.text("name"),
                edifact = node.get("edifact")?.let(::identification),
                documents = documents,
                sftp = sftp,
                pgp = node.get("pgp")?.let(::pgp),
                agreement = agreement,
                contacts =
                    node.get("contacts").values().map {
                        Contact(
                            ContactRole.valueOf(it.text("role").uppercase()),
                            it.text("name"),
                            it.text("email"),
                            it.get("phone")?.asString(),
                        )
                    },
            )
        requirements(partner)
        return if (violations.size == before) partner else null
    }

    /** 扱う文書ごとに要る設定(受け取る文書・送るファイル・返す応答)。 */
    private fun requirements(partner: TradingPartner) {
        val directories = partner.sftp.directories
        val inbound = partner.documents.any { it.direction == Direction.INBOUND }
        val files = partner.documents.any { it.direction == Direction.OUTBOUND && it.dataset != null }
        val acknowledgements = partner.documents.any { it.direction == Direction.OUTBOUND && it.dataset == null }

        fun expect(
            ok: Boolean,
            field: String,
            reason: String,
        ) {
            if (!ok) violations += FieldViolation("$path/$field", reason)
        }
        expect(!inbound || partner.edifact != null, "edifact", "文書を受け取る取引先には、UNB の送り手の識別子が要ります")
        expect(!inbound || directories.inbound != null, "sftp/directories/inbound", "文書を受け取る取引先には、受け取りのディレクトリが要ります")
        expect(!inbound || partner.sftp.completion != null, "sftp/completion", "文書を受け取る取引先には、書き終わりの判断の方式が要ります")
        expect(!files || directories.outbound != null, "sftp/directories/outbound", "ファイルを送る取引先には、送り先のディレクトリが要ります")
        // B2B の文書は、送るものも受け取るものも PGP で暗号化と署名をする(Framework 9.2。ADR-0032 §8・ADR-0040 §8)
        expect(partner.pgp != null, "pgp", "取引先には PGP の公開鍵が要ります(B2B の文書は暗号化と署名が必須。Framework 9.2)")
        expect(!acknowledgements || directories.acknowledgements != null, "sftp/directories/acknowledgements", "応答を返す取引先には、応答のディレクトリが要ります")
        partner.documents.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
            violations += FieldViolation("$path/documents", "文書 ${it.integration} が同じ向き(とデータセット)で重複しています")
        }
    }

    private fun pgp(node: JsonNode): PgpPublicKey =
        PgpPublicKey(
            publicKey = SecretName(node.text("publicKeySecret")),
            validUntil = date(node, "validUntil", "pgp"),
            previous =
                node
                    .get("previousPublicKeys")
                    ?.values()
                    ?.mapIndexed { index, previous ->
                        PreviousPublicKey(
                            SecretName(previous.text("publicKeySecret")),
                            date(previous, "acceptUntil", "pgp/previousPublicKeys/$index"),
                        )
                    }.orEmpty(),
        )

    private fun sftp(node: JsonNode): SftpEndpoint {
        val hostKey = node.get("hostKey")
        val clientKey = node.get("clientKey")
        val directories = node.get("directories")
        return SftpEndpoint(
            host = node.text("host"),
            port = node.get("port").asInt(),
            username = node.text("username"),
            knownHosts = SecretName(hostKey.text("knownHostsSecret")),
            hostKeyValidUntil = date(hostKey, "validUntil", "sftp/hostKey"),
            privateKey = SecretName(clientKey.text("privateKeySecret")),
            privateKeyPassphrase = clientKey.get("passphraseSecret")?.let { SecretName(it.asString()) },
            clientKeyValidUntil = date(clientKey, "validUntil", "sftp/clientKey"),
            directories =
                SftpDirectories(
                    outbound = directories.get("outbound")?.asString(),
                    inbound = directories.get("inbound")?.asString(),
                    acknowledgements = directories.get("acknowledgements")?.asString(),
                ),
            completion = node.get("completion")?.let(::completion),
        )
    }

    private fun completion(node: JsonNode): Completion =
        when (node.text("method")) {
            "rename" -> Completion.Rename(node.text("temporarySuffix"))
            else -> Completion.Stable(duration(node, "quietPeriod", "sftp/completion"))
        }

    private fun agreement(node: JsonNode): ServiceAgreement {
        val acknowledgements = node.get("acknowledgements")
        val cutoff = node.get("cutoff")
        return ServiceAgreement(
            currencies =
                node
                    .get("currencies")
                    .values()
                    .map { it.asString() }
                    .toSet(),
            positiveAperak = acknowledgements.get("positiveAperak").asBoolean(),
            aperakDeadline = duration(acknowledgements, "aperakDeadline", "agreement/acknowledgements"),
            cutoff = LocalTime.parse(cutoff.text("time")),
            cutoffZone = zone(cutoff),
            syntaxAcknowledgementSla = duration(node.get("sla"), "syntaxAcknowledgement", "agreement/sla"),
            resend = node.text("resend"),
        )
    }

    private fun date(
        node: JsonNode,
        field: String,
        location: String,
    ): LocalDate = parsed(location, field, "実在する日付(YYYY-MM-DD)にしてください", LocalDate.EPOCH) { LocalDate.parse(node.text(field)) }

    private fun duration(
        node: JsonNode,
        field: String,
        location: String,
    ): Duration =
        parsed(location, field, "正の ISO 8601 の期間にしてください", Duration.ZERO) {
            Duration.parse(node.text(field)).also { if (it.isZero || it.isNegative) throw DateTimeException("zero") }
        }

    private fun zone(node: JsonNode): ZoneId =
        parsed("agreement/cutoff", "timeZone", "IANA のタイムゾーンにしてください", ZoneId.of("UTC")) {
            ZoneId.of(node.text("timeZone"))
        }

    /** 値を解釈する。解釈できなければ誤りを足し、[fallback] を返す(取引先は誤りとして捨てるので、値は使われない)。 */
    private inline fun <T> parsed(
        location: String,
        field: String,
        reason: String,
        fallback: T,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (_: DateTimeException) {
            violations += FieldViolation("$path/$location/$field", reason)
            fallback
        }
}

private fun identification(node: JsonNode): PartyIdentification = PartyIdentification(node.text("id"), node.text("qualifier"))

private fun JsonNode.text(field: String): String = checkNotNull(get(field)) { "スキーマで確かめた項目 $field がありません" }.asString()
