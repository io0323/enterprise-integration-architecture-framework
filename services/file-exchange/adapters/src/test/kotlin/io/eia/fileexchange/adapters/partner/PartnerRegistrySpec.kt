package io.eia.fileexchange.adapters.partner

import io.eia.fileexchange.domain.PartnerId
import io.eia.platform.security.secret.SecretName
import io.eia.platform.testsupport.TestLedgers
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.kotest.assertions.fail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun Result<PartnerRegistry, ValidationError>.ok(): PartnerRegistry =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private fun Result<PartnerRegistry, ValidationError>.fields(): List<String> =
    when (this) {
        is Result.Ok -> fail("Err を期待しましたが Ok でした")
        is Result.Err -> error.violations.map { it.field }
    }

/** 台帳(platform/trading-partner。ADR-0040)のうち、ファイルを送る取引先から送り先を作る。台帳の検証は platform/trading-partner のテストで行う。 */
class PartnerRegistrySpec :
    FunSpec({
        test("ファイルを送る取引先ごとに、送るデータセット・送り先・ホスト鍵・鍵の名前を持つ。台帳も持つ") {
            val parsed =
                PartnerRegistry
                    .parse(
                        TestLedgers.json(
                            TestLedgers.Partner(knownHostsSecret = "FILE_EXCHANGE_PARTNER01_KNOWN_HOSTS"),
                            TestLedgers.Partner(
                                id = "acme",
                                host = "sftp.acme.example",
                                port = 22,
                                username = "eiaf",
                                outbound = "/in",
                                datasets = listOf("sales_daily", "erp_orders"),
                                knownHostsSecret = "ACME_KNOWN_HOSTS",
                                privateKeySecret = "ACME_SSH_KEY",
                                passphraseSecret = "ACME_SSH_PASSPHRASE",
                                pgpPublicKeySecret = "ACME_PGP_PUBLIC_KEY",
                                edi = false,
                            ),
                        ),
                    ).ok()

            parsed.partners.map { it.id.value } shouldBe listOf("acme", "partner01")
            parsed.ledger?.partners?.map { it.id } shouldBe listOf("acme", "partner01")
            parsed.recipients("sales_daily") shouldBe listOf(PartnerId("acme"), PartnerId("partner01"))
            parsed.recipients("erp_orders") shouldBe listOf(PartnerId("acme"))
            parsed.recipients("other_data") shouldBe emptyList()

            val acme = parsed[PartnerId("acme")]!!
            acme.sftp.host shouldBe "sftp.acme.example"
            acme.sftp.port shouldBe 22
            acme.sftp.privateKeyPassphrase shouldBe SecretName("ACME_SSH_PASSPHRASE")
            acme.directory shouldBe "/in"
            acme.pgpPublicKey shouldBe SecretName("ACME_PGP_PUBLIC_KEY")
            val local = parsed[PartnerId("partner01")]!!
            local.sftp.port shouldBe 2222
            local.sftp.knownHosts shouldBe SecretName("FILE_EXCHANGE_PARTNER01_KNOWN_HOSTS")
            local.sftp.privateKeyPassphrase shouldBe null
            parsed[PartnerId("nobody")] shouldBe null
        }

        test("ファイルを送らない取引先(EDI の文書だけを扱う)は、送り先にしない") {
            PartnerRegistry
                .parse(TestLedgers.json(TestLedgers.Partner(datasets = emptyList())))
                .ok()
                .partners shouldBe emptyList()
        }

        test("台帳の誤りは、台帳の項目の場所で返す") {
            PartnerRegistry.parse("{").fields() shouldBe listOf("partners")
            PartnerRegistry.parse(TestLedgers.json(TestLedgers.Partner(port = 70_000))).fields() shouldBe listOf("/partners/0/sftp/port")
            PartnerRegistry.parse(TestLedgers.json(TestLedgers.Partner(), TestLedgers.Partner())).fields() shouldBe listOf("/partners")
        }

        test("直接作るときも、取引先の ID の重複を許さない") {
            val one =
                PartnerRegistry
                    .parse(TestLedgers.json(TestLedgers.Partner()))
                    .ok()
                    .partners
                    .single()
            shouldThrow<IllegalArgumentException> { PartnerRegistry(listOf(one, one)) }
        }
    })
