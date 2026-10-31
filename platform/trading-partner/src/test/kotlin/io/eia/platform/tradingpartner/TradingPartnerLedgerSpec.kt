package io.eia.platform.tradingpartner

import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import tools.jackson.dataformat.yaml.YAMLMapper
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.io.path.readText

private val LOCAL_LEDGER: String = Path.of(System.getProperty("eia.partnersFile")).readText()

private fun Result<TradingPartnerLedger, ValidationError>.ledger(): TradingPartnerLedger =
    shouldBeInstanceOf<Result.Ok<TradingPartnerLedger>>().value

private fun Result<TradingPartnerLedger, ValidationError>.fields(): List<String> =
    shouldBeInstanceOf<Result.Err<ValidationError>>().error.violations.map { it.field }

/** ローカル基盤の台帳の文字列を [old] → [new] に置き換えて読む(誤りのサンプル)。 */
private fun parseReplacing(vararg replacements: Pair<String, String>): Result<TradingPartnerLedger, ValidationError> {
    var text = LOCAL_LEDGER
    replacements.forEach { (old, new) ->
        check(old in text) { "台帳に置換対象がありません: $old" }
        text = text.replace(old, new)
    }
    return TradingPartnerLedger.parse(text)
}

class TradingPartnerLedgerSpec :
    FunSpec({
        context("ローカル基盤の台帳(infra/local/b2b/partners.json)") {
            test("読めて、TPA の項目を持つ") {
                val ledger = TradingPartnerLedger.parse(LOCAL_LEDGER).ledger()
                val partner = checkNotNull(ledger["partner01"])

                ledger.company shouldBe Company("EIAF (local)", PartyIdentification("4900000000001", "14"), "4900000000001")
                partner.edifact shouldBe PartyIdentification("4912345000019", "14")
                partner.sftp.knownHosts shouldBe SecretName("PARTNER01_SFTP_KNOWN_HOSTS")
                partner.sftp.directories shouldBe SftpDirectories("/config/outbox", "/config/to-eiaf", "/config/from-eiaf")
                partner.sftp.completion shouldBe Completion.Rename(".part")
                partner.pgp shouldBe PgpPublicKey(SecretName("PARTNER01_PGP_PUBLIC_KEY"), LocalDate.parse("2027-10-31"))
                with(partner.agreement) {
                    currencies shouldBe setOf("JPY")
                    positiveAperak shouldBe true
                    aperakDeadline shouldBe Duration.ofHours(4)
                    cutoff shouldBe LocalTime.of(15, 0)
                    cutoffZone shouldBe ZoneId.of("Asia/Tokyo")
                    syntaxAcknowledgementSla shouldBe Duration.ofMinutes(15)
                }
                partner.contacts.map { it.role } shouldContainExactly
                    listOf(ContactRole.BUSINESS, ContactRole.OPERATIONS, ContactRole.INCIDENT)
            }

            test("扱う文書は連携カタログにあり、EDI かファイルの連携。受け取る文書の送り手は取引先(ADR-0041 §7)") {
                val catalogDir = Path.of(System.getProperty("eia.catalogDir"))
                val yaml = YAMLMapper.builder().build()
                TradingPartnerLedger.parse(LOCAL_LEDGER).ledger().partners.flatMap { it.documents }.forEach { document ->
                    val file = catalogDir.resolve("${document.integration}.yaml")
                    check(file.toFile().isFile) { "${document.integration} がカタログにありません" }
                    val catalog = yaml.readTree(file.toFile())
                    catalog.get("style").asString() shouldBe (if (document.dataset != null) "file" else "edi")
                    if (document.direction ==
                        Direction.INBOUND
                    ) {
                        catalog.get("provider").get("system").asString() shouldBe "trading-partners"
                    }
                }
            }

            test("連携と向きで取引先を探す") {
                val ledger = TradingPartnerLedger.parse(LOCAL_LEDGER).ledger()

                ledger.partnersFor("INT-SALES-004", Direction.OUTBOUND, "sales_daily").map { it.id } shouldContainExactly
                    listOf("partner01")
                ledger.partnersFor("INT-SALES-004", Direction.OUTBOUND, "sales_monthly") shouldBe emptyList()
                ledger.partnersFor("INT-B2B-001", Direction.INBOUND).map { it.id } shouldContainExactly listOf("partner01")
                ledger.partnersFor("INT-B2B-001", Direction.OUTBOUND) shouldBe emptyList()
            }
        }

        context("誤りは項目の場所と理由で返す(値は入れない)") {
            test("JSON として読めない・形(JSON Schema)の誤り") {
                TradingPartnerLedger.parse("{").fields() shouldBe listOf("partners")
                parseReplacing("\"version\": 1" to "\"version\": 2").fields() shouldBe listOf("/version")
                parseReplacing(
                    "\"privateKeySecret\": \"PARTNER01_SFTP_KEY\"" to "\"privateKeySecret\": \"partner01-key\"",
                ).fields() shouldBe
                    listOf("/partners/0/sftp/clientKey/privateKeySecret")
                parseReplacing("\"role\": \"business\"" to "\"role\": \"sales\"").fields() shouldBe listOf("/partners/0/contacts/0/role")
            }

            test("実在しない日付・0 の期間・知らないタイムゾーン") {
                parseReplacing(
                    "\"knownHostsSecret\": \"PARTNER01_SFTP_KNOWN_HOSTS\", \"validUntil\": \"2027-10-31\"" to
                        "\"knownHostsSecret\": \"PARTNER01_SFTP_KNOWN_HOSTS\", \"validUntil\": \"2027-02-30\"",
                    "\"aperakDeadline\": \"PT4H\"" to "\"aperakDeadline\": \"PT0S\"",
                    "Asia/Tokyo" to "Asia/Nowhere",
                ).fields() shouldContainExactlyInAnyOrder
                    listOf(
                        "/partners/0/sftp/hostKey/validUntil",
                        "/partners/0/agreement/acknowledgements/aperakDeadline",
                        "/partners/0/agreement/cutoff/timeZone",
                    )
            }

            test("扱う文書ごとに要る設定: 受け取るなら送り手・受け取りのディレクトリ・書き終わりの判断、送るファイルには PGP、応答にはそのディレクトリ") {
                parseReplacing(
                    "\"edifact\": { \"id\": \"4912345000019\", \"qualifier\": \"14\" },\n      \"documents\"" to "\"documents\"",
                    "          \"inbound\": \"/config/to-eiaf\",\n" to "",
                    ",\n        \"completion\": { \"method\": \"rename\", \"temporarySuffix\": \".part\" }" to "",
                    "\"pgp\": { \"publicKeySecret\": \"PARTNER01_PGP_PUBLIC_KEY\", \"validUntil\": \"2027-10-31\" },\n" to "",
                    ",\n          \"acknowledgements\": \"/config/from-eiaf\"" to "",
                ).fields() shouldContainExactlyInAnyOrder
                    listOf(
                        "/partners/0/edifact",
                        "/partners/0/sftp/directories/inbound",
                        "/partners/0/sftp/completion",
                        "/partners/0/pgp",
                        "/partners/0/sftp/directories/acknowledgements",
                    )
            }

            test("取引先の ID の重複・同じ文書を同じ向きで 2 回") {
                val partner = LOCAL_LEDGER.substringAfter("\"partners\": [").substringBeforeLast("]")
                TradingPartnerLedger.parse(LOCAL_LEDGER.replace(partner, "$partner,$partner")).fields() shouldBe listOf("/partners")
                val inbound = "{ \"integration\": \"INT-B2B-001\", \"direction\": \"inbound\" },"
                parseReplacing(inbound to "$inbound $inbound").fields() shouldBe listOf("/partners/0/documents")
            }

            test("書き終わりの判断は、改名(rename)か、大きさが変わらない時間(stable)") {
                val rename = "{ \"method\": \"rename\", \"temporarySuffix\": \".part\" }"
                parseReplacing(rename to "{ \"method\": \"stable\", \"quietPeriod\": \"PT60S\" }")
                    .ledger()["partner01"]
                    ?.sftp
                    ?.completion shouldBe Completion.Stable(Duration.ofSeconds(60))
                parseReplacing(
                    "{ \"method\": \"rename\", \"temporarySuffix\": \".part\" }" to "{ \"method\": \"rename\" }",
                ).fields().first() shouldContain
                    "/partners/0/sftp/completion"
            }

            test("台帳を直接作るときも、取引先の ID の重複は誤り") {
                val partner = checkNotNull(TradingPartnerLedger.parse(LOCAL_LEDGER).ledger()["partner01"])
                shouldThrow<IllegalArgumentException> {
                    TradingPartnerLedger(Company("c", PartyIdentification("1", "14"), "1"), listOf(partner, partner))
                }
            }
        }
    })
