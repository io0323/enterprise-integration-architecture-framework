package io.eia.fileexchange.adapters.partner

import io.eia.fileexchange.domain.PartnerId
import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.kotest.assertions.fail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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

private fun partner(
    id: String = "partner01",
    datasets: String = "\"sales_daily\"",
    sftp: String =
        """
        "host": "sftp-b2b", "port": 2222, "username": "partner01", "directory": "/config/outbox",
        "knownHostsSecret": "FILE_EXCHANGE_PARTNER01_KNOWN_HOSTS", "privateKeySecret": "FILE_EXCHANGE_PARTNER01_SSH_KEY"
        """.trimIndent(),
    pgp: String = "\"publicKeySecret\": \"FILE_EXCHANGE_PARTNER01_PGP_PUBLIC_KEY\"",
) = """{ "id": "$id", "datasets": [$datasets], "sftp": { $sftp }, "pgp": { $pgp } }"""

private fun registry(vararg partners: String) = """{ "partners": [${partners.joinToString(",")}] }"""

class PartnerRegistrySpec :
    FunSpec({
        test("取引先の ID ごとに、送るデータセット・送り先・ホスト鍵・鍵の名前を持つ") {
            val parsed =
                PartnerRegistry
                    .parse(
                        registry(
                            partner(),
                            partner(
                                id = "acme",
                                datasets = "\"sales_daily\", \"erp_orders\"",
                                sftp =
                                    """
                                    "host": "sftp.acme.example", "username": "eiaf", "directory": "/in",
                                    "knownHostsSecret": "ACME_KNOWN_HOSTS", "privateKeySecret": "ACME_SSH_KEY",
                                    "privateKeyPassphraseSecret": "ACME_SSH_PASSPHRASE"
                                    """.trimIndent(),
                                pgp = "\"publicKeySecret\": \"ACME_PGP_PUBLIC_KEY\"",
                            ),
                        ),
                    ).ok()

            parsed.partners.map { it.id.value } shouldBe listOf("acme", "partner01")
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

        test("誤りは項目のパスで返す") {
            PartnerRegistry
                .parse(
                    registry(
                        partner(
                            id = "Bad_ID",
                            datasets = "\"Sales\"",
                            sftp =
                                """
                                "host": " ", "port": 70000, "username": "", "directory": "relative",
                                "knownHostsSecret": "lower", "privateKeySecret": "KEY_FILE", "privateKeyPassphraseSecret": "x"
                                """.trimIndent(),
                            pgp = "\"publicKeySecret\": \"\"",
                        ),
                        partner(datasets = ""),
                    ),
                ).fields() shouldContainExactlyInAnyOrder
                listOf(
                    "partners[0].id",
                    "partners[0].datasets[0]",
                    "partners[0].sftp.host",
                    "partners[0].sftp.port",
                    "partners[0].sftp.username",
                    "partners[0].sftp.directory",
                    "partners[0].sftp.knownHostsSecret",
                    "partners[0].sftp.privateKeySecret",
                    "partners[0].sftp.privateKeyPassphraseSecret",
                    "partners[0].pgp.publicKeySecret",
                    "partners[1].datasets",
                )
        }

        test("取引先の ID の重複・JSON として読めない・知らない項目は誤り") {
            PartnerRegistry.parse(registry(partner(), partner())).fields() shouldBe listOf("partners")
            PartnerRegistry.parse("{").fields() shouldBe listOf("partners")
            PartnerRegistry.parse("""{ "partners": [], "extra": 1 }""").fields() shouldBe listOf("partners")
            PartnerRegistry.parse("""{ "partners": [{ "id": "a" }] }""").fields() shouldBe listOf("partners")
            PartnerRegistry.parse(registry()).ok().partners shouldBe emptyList()
        }

        test("直接作るときも、取引先の ID の重複を許さない") {
            val one =
                PartnerRegistry
                    .parse(registry(partner()))
                    .ok()
                    .partners
                    .single()
            shouldThrow<IllegalArgumentException> { PartnerRegistry(listOf(one, one)) }
        }
    })
