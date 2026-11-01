@file:Suppress("MagicNumber") // SQL の列の位置・テストの日付と鍵の長さ

package io.eia.b2bgateway.adapters

import io.eia.b2bgateway.adapters.archive.ArchiveKeyNames
import io.eia.b2bgateway.adapters.archive.S3OriginalArchive
import io.eia.b2bgateway.adapters.archive.S3OriginalReader
import io.eia.b2bgateway.adapters.crypto.PgpDocumentCrypto
import io.eia.b2bgateway.adapters.inbox.SftpPartnerInbox
import io.eia.b2bgateway.adapters.outbox.SftpPartnerOutbox
import io.eia.b2bgateway.adapters.partner.LedgerPartnerAgreements
import io.eia.b2bgateway.adapters.persistence.B2bGatewaySchema
import io.eia.b2bgateway.adapters.persistence.JdbcAcknowledgements
import io.eia.b2bgateway.adapters.persistence.JdbcControlReferences
import io.eia.b2bgateway.adapters.persistence.JdbcReceipts
import io.eia.b2bgateway.adapters.spec.YamlEdiSpecs
import io.eia.b2bgateway.application.port.inbound.AcceptedDocument
import io.eia.b2bgateway.application.port.inbound.DocumentOutcome
import io.eia.b2bgateway.application.port.inbound.IncomingDocument
import io.eia.b2bgateway.application.port.inbound.PollSummary
import io.eia.b2bgateway.application.port.inbound.SendSummary
import io.eia.b2bgateway.application.port.outbound.CodeLookup
import io.eia.b2bgateway.application.port.outbound.CodeMappings
import io.eia.b2bgateway.application.usecase.AcceptDocumentService
import io.eia.b2bgateway.application.usecase.PollInboxService
import io.eia.b2bgateway.application.usecase.ReceiveInterchangeService
import io.eia.b2bgateway.application.usecase.ReceivingPorts
import io.eia.b2bgateway.application.usecase.SendAcknowledgementsService
import io.eia.b2bgateway.domain.order.CodeResolution
import io.eia.b2bgateway.domain.order.InternalItem
import io.eia.b2bgateway.domain.order.ItemCode
import io.eia.platform.audit.AuditError
import io.eia.platform.audit.anchor.AnchorStore
import io.eia.platform.audit.anchor.AnchorVersion
import io.eia.platform.audit.anchor.ServiceName
import io.eia.platform.audit.jdbc.AuditLog
import io.eia.platform.audit.verify.AuditVerification
import io.eia.platform.filetransfer.pgp.PgpFiles
import io.eia.platform.filetransfer.pgp.PgpKeys
import io.eia.platform.filetransfer.pgp.PgpVerification
import io.eia.platform.filetransfer.sftp.SftpConfig
import io.eia.platform.filetransfer.sftp.SftpSender
import io.eia.platform.filetransfer.store.S3ObjectStore
import io.eia.platform.filetransfer.store.S3StoreConfig
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.platform.testsupport.InfraImages
import io.eia.platform.testsupport.SeaweedFsContainer
import io.eia.platform.testsupport.SftpServerContainer
import io.eia.platform.testsupport.TestKeys
import io.eia.platform.testsupport.TestLedgers
import io.eia.platform.tradingpartner.TradingPartnerLedger
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.FixedClock
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private const val PARTNER = "partner01"
private const val BUCKET = "eiaf-b2b-originals"
private const val INBOUND = "/config/to-eiaf"
private const val ACKNOWLEDGEMENTS = "/config/from-eiaf"

/** 進め方を決められる時計(書き終わりの判断 stable の静かな時間)。 */
private class MutableClock(
    private var now: Instant,
) : java.time.Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now

    override fun getZone(): java.time.ZoneId = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
}

private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** 契約のサンプルの注文(edifact-orders.v1。明細 2 つ)の交換。[reference] は交換の管理番号、[item] は 2 つ目の明細の GTIN。 */
private fun orders(
    reference: String,
    orderNumber: String = "PO-$reference",
    item: String = "4901234567900",
): ByteArray {
    val body =
        listOf(
            "BGM+220+$orderNumber+9",
            "DTM+137:20261030:102",
            "NAD+BY+4912345000019::9",
            "NAD+SU+4900000000001::9",
            "CUX+2:JPY:9",
            "LIN+1++4901234567894:EN",
            "PIA+1+ABC-123:BP",
            "QTY+21:10",
            "PRI+AAA:1200",
            "LIN+2++$item:EN",
            "QTY+21:5",
            "PRI+AAA:980",
            "UNS+S",
            "CNT+2:2",
        )
    val unb = "UNB+UNOA:3+4912345000019:14+4900000000001:14+261030:1200+$reference"
    val segments = listOf(unb, "UNH+1+ORDERS:D:96A:UN") + body + listOf("UNT+${body.size + 2}+1", "UNZ+1+$reference")
    return ("UNA:+.? '" + segments.joinToString("'", postfix = "'")).encodeToByteArray()
}

/** 取引先の対応表の代わり(社内への取り込みの表は P12 ⑤)。サンプルの買い手と商品だけを持つ。 */
private object SampleMappings : CodeMappings {
    override suspend fun resolve(
        partnerId: String,
        lookup: CodeLookup,
    ): Result<CodeResolution, DomainError> =
        ok(
            CodeResolution(
                buyers = mapOf("4912345000019" to "cust-1"),
                items =
                    mapOf(
                        ItemCode.BuyerPartNumber("ABC-123") to InternalItem("prod-1", "SKU-1"),
                        ItemCode.Gtin("4901234567900") to InternalItem("prod-2", "SKU-2"),
                    ),
            ),
        )
}

/** アンカーのない監査の検証(チェーンと件数だけを確かめる。アンカーの保存は P12 ⑥)。 */
private object NoAnchors : AnchorStore {
    override fun put(
        key: String,
        body: ByteArray,
        retainUntil: Instant,
    ): Result<String, AuditError> = error("使わない")

    override fun listVersions(prefix: String): Result<List<AnchorVersion>, AuditError> = ok(emptyList())

    override fun latest(prefix: String): Result<AnchorVersion?, AuditError> = ok(null)
}

/**
 * 受け取った文書の受け付け(P12 ④a。ADR-0040 §5・§8、ADR-0041 §11)を、PostgreSQL と SeaweedFS(Object Lock)で確かめる。
 * - 原本(PGP の暗号文)を COMPLIANCE で手を加えずに保管し、復号した交換を保管用の鍵で暗号化して別に保管する
 * - 読めない文書(壊れた暗号文・取引先の鍵でない署名)も原本を保管し、「復号の段階で拒否」として監査に記録する
 * - 送り直し(暗号文は違うが、復号した交換が同じ)は、前の応答を送り直す。管理番号の使い回しは 26 で拒否する
 * - 署名の鍵の差し替えの間は、旧い鍵の署名も受け付ける(台帳の acceptUntil まで)
 * - すべての文書が監査に記録され、ハッシュチェーンの検証が通る(DoD)
 */
class AcceptDocumentIT :
    FunSpec({
        val postgres = PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
        val seaweed = SeaweedFsContainer(mapOf("eiaf" to listOf("Admin", "Read", "Write", "List")))
        // 取引先の SFTP(受け取りのディレクトリ /config/to-eiaf。P12 ④b1)
        val sshKey = TestKeys.ssh()
        val sftp = SftpServerContainer(PARTNER, sshKey.publicKey)
        val workDir: Path = Files.createTempDirectory("b2b-accept-it")
        val ownerPassword = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))
        val appPassword = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))
        val now = Instant.parse("2026-10-31T00:00:00Z")
        val sequence = AtomicInteger()

        // 自社・取引先(今の鍵と旧い鍵)・関係のない第三者・自社の保管用の鍵
        val ours = TestKeys.pgp("EIAF <eiaf@example.com>")
        val partner = TestKeys.pgp("Partner <partner@example.com>")
        val partnerOld = TestKeys.pgp("Partner old <partner@example.com>")
        val stranger = TestKeys.pgp("Stranger <stranger@example.com>")
        val archiveKey = TestKeys.pgp("EIAF archive <archive@example.com>")

        lateinit var app: PGSimpleDataSource
        lateinit var admin: S3Client

        fun dataSource(
            database: String,
            user: String,
            password: String,
        ) = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl.replaceAfterLast('/', database))
            this.user = user
            this.password = password
        }

        fun secrets(): EnvSecretProvider {
            val credentials = seaweed.credentials.getValue("eiaf")
            return EnvSecretProvider(
                mapOf(
                    "IT_S3_ACCESS_KEY" to credentials.accessKey,
                    "IT_S3_SECRET_KEY" to credentials.secretKey,
                    "P01_PGP_PUBLIC_KEY" to partner.publicKey,
                    "P01_PGP_PUBLIC_KEY_OLD" to partnerOld.publicKey,
                    "B2B_PGP_SECRET_KEY" to ours.secretKey,
                    "B2B_PGP_PASSPHRASE" to ours.passphrase,
                    "B2B_ARCHIVE_PGP_PUBLIC_KEY" to archiveKey.publicKey,
                    "P01_SSH_KEY" to sshKey.privateKey,
                    "P01_KNOWN_HOSTS" to sftp.knownHostsLine(),
                ),
            )
        }

        /** 台帳([acceptOldUntil] があれば、取引先の旧い鍵をその日まで受け付ける)から、受け付けの部品を組み立てる。 */
        fun accept(acceptOldUntil: LocalDate? = null): AcceptDocumentService {
            val previous = acceptOldUntil?.let { mapOf("P01_PGP_PUBLIC_KEY_OLD" to it.toString()) }.orEmpty()
            val ledger =
                (TradingPartnerLedger.parse(TestLedgers.json(TestLedgers.Partner(previousPgpPublicKeys = previous))) as Result.Ok).value
            val secrets = secrets()
            val store =
                S3ObjectStore(
                    S3StoreConfig(URI(seaweed.endpoint), BUCKET, SecretName("IT_S3_ACCESS_KEY"), SecretName("IT_S3_SECRET_KEY")),
                    secrets,
                )
            val clock = java.time.Clock.fixed(now.plusSeconds(sequence.incrementAndGet().toLong()), ZoneOffset.UTC)
            val receipts = JdbcReceipts(app, AuditLog())
            val agreements = (LedgerPartnerAgreements.of(ledger) as Result.Ok).value
            val receive =
                ReceiveInterchangeService(
                    YamlEdiSpecs(),
                    ReceivingPorts(agreements, receipts, SampleMappings, receipts, JdbcControlReferences(app)),
                    FixedClock(kotlin.time.Instant.parse(clock.instant().toString())),
                )
            return AcceptDocumentService(
                receive = receive,
                archive =
                    S3OriginalArchive(
                        store,
                        secrets,
                        ArchiveKeyNames(
                            SecretName("B2B_ARCHIVE_PGP_PUBLIC_KEY"),
                            SecretName("B2B_PGP_SECRET_KEY"),
                            SecretName("B2B_PGP_PASSPHRASE"),
                        ),
                        Duration.ofMinutes(3),
                        workDir,
                        // Object Lock の保持期限は、ストレージの実際の時刻から数える(固定の時計だと期限が過去になる)
                        java.time.Clock.systemUTC(),
                    ),
                crypto =
                    PgpDocumentCrypto(
                        ledger,
                        secrets,
                        SecretName("B2B_PGP_SECRET_KEY"),
                        SecretName("B2B_PGP_PASSPHRASE"),
                        workDir,
                        clock,
                    ),
                records = receipts,
                clock = FixedClock(kotlin.time.Instant.parse(clock.instant().toString())),
            )
        }

        /** 取引先の SFTP の受け取りのディレクトリ(台帳の接続先はテストのコンテナ。[clock] は書き終わりの判断 stable の時刻)。 */
        fun inbox(
            stableQuietPeriod: String? = null,
            clock: java.time.Clock = java.time.Clock.systemUTC(),
        ): SftpPartnerInbox {
            val ledger =
                (
                    TradingPartnerLedger.parse(
                        TestLedgers.json(TestLedgers.Partner(host = sftp.host, port = sftp.sshPort, stableQuietPeriod = stableQuietPeriod)),
                    ) as Result.Ok
                ).value
            val resilience = Resilience("sftp-$PARTNER", ResilienceConfig(attemptTimeout = 30.seconds, retry = null))
            return SftpPartnerInbox(ledger, secrets(), mapOf(PARTNER to resilience), clock = clock)
        }

        suspend fun poll(inbox: SftpPartnerInbox = inbox()): PollSummary =
            PollInboxService(inbox, accept())(PARTNER).shouldBeInstanceOf<Result.Ok<PollSummary>>().value

        /** 取引先の側: 受け取りのディレクトリに置く(SFTP の利用者として。一時名 .part で書いてから正式名に変える)。 */
        fun place(
            name: String,
            content: ByteArray,
        ) {
            val sender =
                SftpSender(
                    SftpConfig(sftp.host, sftp.sshPort, PARTNER, SecretName("P01_SSH_KEY"), SecretName("P01_KNOWN_HOSTS")),
                    secrets(),
                )
            val file = Files.createTempFile(workDir, "place", ".pgp").also { Files.write(it, content) }
            (sender.send(file, INBOUND, name) as Result.Ok).value
        }

        /** 取引先の側: [signer] の鍵で署名し、自社の公開鍵あてに暗号化する。 */
        fun sealedByPartner(
            plain: ByteArray,
            signer: TestKeys.Pgp = partner,
        ): ByteArray {
            val keys = (PgpKeys.parse(Secret(ours.publicKey), Secret(signer.secretKey), Secret(signer.passphrase)) as Result.Ok).value
            val source = Files.createTempFile(workDir, "plain", ".edi").also { Files.write(it, plain) }
            val target = Files.createTempFile(workDir, "sealed", ".pgp")
            PgpFiles.encryptAndSign(source, target, keys, "orders.edi")
            return Files.readAllBytes(target)
        }

        /** [keyOwner] の秘密鍵で復号し、[signer] の公開鍵で署名を確かめる(取引先の側・保管の担当の側の読み方)。 */
        fun open(
            sealed: ByteArray,
            keyOwner: TestKeys.Pgp,
            signer: TestKeys.Pgp,
        ): ByteArray {
            val keys = (PgpKeys.parse(Secret(signer.publicKey), Secret(keyOwner.secretKey), Secret(keyOwner.passphrase)) as Result.Ok).value
            val source = Files.createTempFile(workDir, "sealed", ".pgp").also { Files.write(it, sealed) }
            val target = workDir.resolve("opened-${sequence.incrementAndGet()}.bin")
            (PgpFiles.decryptAndVerify(source, target, keys) as Result.Ok).value.shouldBeInstanceOf<PgpVerification.Verified>()
            return Files.readAllBytes(target)
        }

        /** 参照(`{キー}#{版}`)の版を読む。 */
        fun stored(ref: String): ByteArray {
            val (key, version) = ref.split('#')
            return admin.getObjectAsBytes { it.bucket(BUCKET).key(key).versionId(version) }.asByteArray()
        }

        suspend fun receive(
            content: ByteArray,
            service: AcceptDocumentService = accept(),
        ): AcceptedDocument =
            service(IncomingDocument(PARTNER, "orders-${sequence.incrementAndGet()}.edi.pgp", content))
                .shouldBeInstanceOf<Result.Ok<AcceptedDocument>>()
                .value

        fun <T> query(
            sql: String,
            vararg parameters: Any,
            row: (java.sql.ResultSet) -> T,
        ): List<T> =
            app.connection.use { c ->
                c.prepareStatement(sql).use { statement ->
                    parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                    statement.executeQuery().use { rs -> generateSequence { if (rs.next()) row(rs) else null }.toList() }
                }
            }

        /** 受け取りの記録の監査(action・outcome・原本の SHA-256・段階)。 */
        fun audit(documentId: String): List<String> =
            query(
                "SELECT outcome, payload_sha256, payload_ref, details->>'stage' FROM audit.audit_log " +
                    "WHERE action = 'b2b.document.receive' AND target_id = ?",
                documentId,
            ) { "${it.getString(1)} ${it.getString(2)} ${it.getString(3)} ${it.getString(4)}" }

        /** 応答の送信(送信の仕組みの送り先はテストのコンテナ)。 */
        suspend fun sendAcknowledgements(): SendSummary {
            val partnerAtContainer = TestLedgers.Partner(host = sftp.host, port = sftp.sshPort)
            val ledger = (TradingPartnerLedger.parse(TestLedgers.json(partnerAtContainer)) as Result.Ok).value
            val secrets = secrets()
            val store =
                S3ObjectStore(
                    S3StoreConfig(URI(seaweed.endpoint), BUCKET, SecretName("IT_S3_ACCESS_KEY"), SecretName("IT_S3_SECRET_KEY")),
                    secrets,
                )
            val crypto =
                PgpDocumentCrypto(ledger, secrets, SecretName("B2B_PGP_SECRET_KEY"), SecretName("B2B_PGP_PASSPHRASE"), workDir)
            val resilience = Resilience("sftp-$PARTNER", ResilienceConfig(attemptTimeout = 30.seconds, retry = null))
            val outbox = SftpPartnerOutbox(ledger, secrets, mapOf(PARTNER to resilience), crypto, workDir)
            val service = SendAcknowledgementsService(JdbcAcknowledgements(app, AuditLog()), S3OriginalReader(store), outbox, ::sha256)
            return service(PARTNER).shouldBeInstanceOf<Result.Ok<SendSummary>>().value
        }

        /** 受け取りの記録の応答(ID・種類・原本の参照)。 */
        fun acknowledgements(documentId: String): List<Triple<String, String, String>> =
            query(
                "SELECT id::text, kind, original_ref FROM b2b.acknowledgement WHERE document_id = ?::uuid ORDER BY kind",
                documentId,
            ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }

        beforeSpec {
            postgres.start()
            seaweed.start()
            sftp.start()
            dataSource(postgres.databaseName, postgres.username, postgres.password).connection.use { c ->
                c.createStatement().use { s ->
                    s.execute("CREATE ROLE b2b_gateway LOGIN PASSWORD '$ownerPassword'")
                    s.execute("CREATE ROLE ${B2bGatewaySchema.APP_ROLE} LOGIN PASSWORD '$appPassword'")
                    s.execute("CREATE DATABASE b2b_gateway OWNER b2b_gateway")
                }
            }
            (B2bGatewaySchema.migrate(dataSource("b2b_gateway", "b2b_gateway", ownerPassword)) as Result.Ok).value
            app = dataSource("b2b_gateway", B2bGatewaySchema.APP_ROLE, appPassword)
            val credentials = seaweed.credentials.getValue("eiaf")
            admin =
                S3Client
                    .builder()
                    .endpointOverride(URI(seaweed.endpoint))
                    .region(Region.US_EAST_1)
                    .forcePathStyle(true)
                    .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(credentials.accessKey, credentials.secretKey)),
                    ).httpClient(UrlConnectionHttpClient.create())
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                    .build()
            admin.createBucket { it.bucket(BUCKET).objectLockEnabledForBucket(true) }
        }

        afterSpec {
            admin.close()
            workDir.toFile().deleteRecursively()
        }

        test("受け付け: 原本を COMPLIANCE で手を加えずに保管し、復号した交換は保管用の鍵で読める。CONTRL は取引先あての暗号文で、送信待ち") {
            val plain = orders("ICR1001")
            val sealed = sealedByPartner(plain)

            val accepted = receive(sealed)

            accepted.outcome shouldBe DocumentOutcome.CHECKED
            val row =
                query(
                    "SELECT original_ref, original_sha256, readable_ref, interchange_sha256 FROM b2b.received_document WHERE id = ?::uuid",
                    accepted.documentId,
                ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }.single()
            val originalRef = row[0]
            val originalSha = row[1]
            val readableRef = row[2]
            val interchangeSha = row[3]
            stored(originalRef).toList() shouldBe sealed.toList()
            originalSha shouldBe sha256(sealed)
            interchangeSha shouldBe sha256(plain)
            val (key, version) = originalRef.split('#')
            admin.getObjectRetention { it.bucket(BUCKET).key(key).versionId(version) }.retention().modeAsString() shouldBe "COMPLIANCE"
            // 保管の担当(保管用の鍵の秘密鍵を持つ)が、自社の署名を確かめて読める
            open(stored(readableRef), archiveKey, ours).toList() shouldBe plain.toList()
            // CONTRL: 取引先が自社の署名を確かめて読める
            val contrl =
                query("SELECT kind, original_ref, state FROM b2b.acknowledgement WHERE document_id = ?::uuid", accepted.documentId) {
                    Triple(it.getString(1), it.getString(2), it.getString(3))
                }.single()
            contrl.first shouldBe "CONTRL"
            contrl.third shouldBe "PENDING"
            open(stored(contrl.second), partner, ours).decodeToString() shouldContain "UCI+ICR1001+4912345000019:14+4900000000001:14+7'"
            query("SELECT order_number FROM b2b.received_order WHERE document_id = ?::uuid", accepted.documentId) {
                it.getString(1)
            } shouldContainExactly
                listOf("PO-ICR1001")
            audit(accepted.documentId) shouldContainExactly listOf("success ${sha256(sealed)} $originalRef none")
        }

        test("送り直し: 暗号文は違っても、復号した交換が同じなら取り込まず、前の応答と同じ原本を送信待ちにする") {
            val plain = orders("ICR2001")
            val first = receive(sealedByPartner(plain))
            val again = sealedByPartner(plain)
            (sha256(again) == sha256(sealedByPartner(plain))) shouldBe false

            val resent = receive(again)

            resent.outcome shouldBe DocumentOutcome.RESENT
            val refs = { id: String ->
                query("SELECT original_ref FROM b2b.acknowledgement WHERE document_id = ?::uuid", id) { it.getString(1) }
            }
            refs(resent.documentId) shouldBe refs(first.documentId)
            query("SELECT first_receipt, previous_document::text FROM b2b.received_document WHERE id = ?::uuid", resent.documentId) {
                it.getBoolean(1) to it.getString(2)
            }.single() shouldBe (false to first.documentId)
            audit(resent.documentId).single() shouldContain "success"
        }

        test("管理番号の使い回し(同じ番号で中身が違う)は、交換を 26 で拒否する") {
            receive(sealedByPartner(orders("ICR3001")))

            val reused = receive(sealedByPartner(orders("ICR3001", orderNumber = "PO-OTHER")))

            reused.outcome shouldBe DocumentOutcome.REJECTED
            val contrl =
                query("SELECT original_ref FROM b2b.acknowledgement WHERE document_id = ?::uuid", reused.documentId) {
                    it.getString(1)
                }.single()
            open(stored(contrl), partner, ours).decodeToString() shouldContain "+4+26+UNB+5'"
            audit(reused.documentId).single() shouldContain "failure"
        }

        test("業務の段階で拒否した注文には APERAK(RE)を送信待ちにし、注文は受け付けない") {
            val rejected = receive(sealedByPartner(orders("ICR4001", item = "4999999999999")))

            rejected.outcome shouldBe DocumentOutcome.CHECKED
            val kinds =
                query("SELECT kind, original_ref FROM b2b.acknowledgement WHERE document_id = ?::uuid ORDER BY kind", rejected.documentId) {
                    it.getString(1) to it.getString(2)
                }
            kinds.map { it.first } shouldContainExactly listOf("APERAK", "CONTRL")
            open(stored(kinds.first().second), partner, ours).decodeToString() shouldContain "ERC+ORD004::ZZZ'FTX+AAO+++UNKNOWN ITEM'"
            query("SELECT 1 FROM b2b.received_order WHERE document_id = ?::uuid", rejected.documentId) { it.getInt(1) }.shouldBeEmpty()
            audit(rejected.documentId).single() shouldContain " business"
        }

        test("読めない文書(壊れた暗号文・取引先の鍵でない署名)も原本を保管し、「復号の段階で拒否」として監査に記録する。応答はしない") {
            listOf(
                "NOT A PGP MESSAGE".encodeToByteArray() to "pgp_not_encrypted",
                sealedByPartner(orders("ICR5001"), signer = stranger) to "pgp_unknown_signer",
            ).forEach { (content, reason) ->
                val unreadable = receive(content)

                unreadable.outcome shouldBe DocumentOutcome.UNREADABLE
                val (originalRef, refusal) =
                    query("SELECT original_ref, refusal FROM b2b.received_document WHERE id = ?::uuid", unreadable.documentId) {
                        it.getString(1) to it.getString(2)
                    }.single()
                refusal shouldBe reason
                stored(originalRef).toList() shouldBe content.toList()
                query(
                    "SELECT 1 FROM b2b.acknowledgement WHERE document_id = ?::uuid",
                    unreadable.documentId,
                ) { it.getInt(1) }.shouldBeEmpty()
                audit(unreadable.documentId) shouldContainExactly listOf("failure ${sha256(content)} $originalRef decryption")
            }
        }

        test("署名の鍵の差し替えの間は、旧い鍵の署名も受け付ける。台帳の受け付けの期日を過ぎたら、取引先の鍵でない署名として拒否する") {
            val byOld = sealedByPartner(orders("ICR6001"), signer = partnerOld)

            receive(byOld, accept(acceptOldUntil = LocalDate.parse("2026-11-30"))).outcome shouldBe DocumentOutcome.CHECKED
            receive(sealedByPartner(orders("ICR6002"), signer = partnerOld), accept(acceptOldUntil = LocalDate.parse("2026-10-30")))
                .outcome shouldBe DocumentOutcome.UNREADABLE
            receive(sealedByPartner(orders("ICR6003")), accept(acceptOldUntil = LocalDate.parse("2026-11-30"))).outcome shouldBe
                DocumentOutcome.CHECKED
        }

        test("見回り(rename): 書き終わった正式名のファイルだけを押さえて受け付け、処理済みに移す。一時名(.part)のファイルは拾わない") {
            place("orders-p1.edi.pgp", sealedByPartner(orders("ICR7001")))
            sftp.writeFile("$INBOUND/orders-p2.edi.pgp.part", "partial".encodeToByteArray())

            val summary = poll()

            summary.failed.shouldBeEmpty()
            val accepted = summary.accepted.single()
            accepted.outcome shouldBe DocumentOutcome.CHECKED
            sftp.list(INBOUND).filter { it.endsWith(".pgp") || it.endsWith(".part") } shouldContainExactly listOf("orders-p2.edi.pgp.part")
            sftp.list("$INBOUND/processed") shouldContainExactly listOf("${accepted.documentId}-orders-p1.edi.pgp")
            sftp.list("$INBOUND/processing").shouldBeEmpty()
            query(
                "SELECT file_name FROM b2b.received_document WHERE id = ?::uuid",
                accepted.documentId,
            ) { it.getString(1) } shouldContainExactly
                listOf("orders-p1.edi.pgp")
            sftp.execInContainer("rm", "$INBOUND/orders-p2.edi.pgp.part")
        }

        test("見回り: 前の処理が押さえたまま落ちたファイル(処理中のディレクトリ)を続きから処理する。読めない文書も記録して処理済みにする") {
            sftp.execInContainer("sh", "-c", "mkdir -p $INBOUND/processing && chown 1000:1000 $INBOUND/processing")
            sftp.writeFile("$INBOUND/processing/orders-left.edi.pgp", sealedByPartner(orders("ICR7101")))
            place("garbage.edi.pgp", "NOT A PGP MESSAGE".encodeToByteArray())

            val summary = poll()

            summary.accepted.map { it.outcome }.toSet() shouldBe setOf(DocumentOutcome.CHECKED, DocumentOutcome.UNREADABLE)
            sftp.list("$INBOUND/processing").shouldBeEmpty()
            summary.accepted.forEach { audit(it.documentId).single() }
        }

        test("見回り(stable): 前の見回りから大きさと更新の時刻が変わらず、静かな時間が経ったファイルだけを拾う") {
            val clock = MutableClock(Instant.now())
            val stable = inbox(stableQuietPeriod = "PT60S", clock = clock)
            place("orders-s1.edi.pgp", sealedByPartner(orders("ICR7201")))

            poll(stable).accepted.shouldBeEmpty() // 初めて見たファイル
            poll(stable).accepted.shouldBeEmpty() // 変わっていないが、静かな時間が経っていない
            clock.advance(Duration.ofSeconds(61))
            poll(stable).accepted.map { it.outcome } shouldContainExactly listOf(DocumentOutcome.CHECKED)
        }

        test("応答の送信: 送信待ちの CONTRL・APERAK を、保管した原本の暗号文のまま応答のディレクトリに送る。取引先は復号して自社の署名を確かめられる") {
            val rejected = receive(sealedByPartner(orders("ICR8001", item = "4999999999999")))
            val acks = acknowledgements(rejected.documentId)

            val summary = sendAcknowledgements()

            summary.failed.shouldBeEmpty()
            acks.map { it.second } shouldContainExactly listOf("APERAK", "CONTRL")
            acks.forEach { (id, kind, ref) ->
                val remote = sftp.readFile("$ACKNOWLEDGEMENTS/${kind.lowercase()}-$id.edi.pgp").shouldNotBeNull()
                remote shouldBe stored(ref)
                open(remote, keyOwner = partner, signer = ours).decodeToString() shouldContain "+$kind:D:"
            }
            query("SELECT count(*) FROM b2b.acknowledgement WHERE state = 'PENDING'") { it.getLong(1) }.single() shouldBe 0L
            val audited =
                query(
                    "SELECT a.outcome, a.destination, a.payload_ref, a.details->>'remote_name', a.details->>'already_present' " +
                        "FROM audit.audit_log a JOIN b2b.acknowledgement k ON a.target_id = k.id::text " +
                        "WHERE a.action = 'b2b.ack.send' AND k.document_id = ?::uuid ORDER BY k.kind",
                    rejected.documentId,
                ) { (1..5).joinToString(" ") { i -> it.getString(i) } }
            audited shouldContainExactly
                acks.map { (id, kind, ref) -> "success partner:$PARTNER $ref ${kind.lowercase()}-$id.edi.pgp false" }
        }

        test("応答の送信: 取引先の側にすでに同じ正式名があれば(前の送信が記録の前に落ちた)、上書きせずに送った印を付ける") {
            val accepted = receive(sealedByPartner(orders("ICR8101")))
            val (id, kind, _) = acknowledgements(accepted.documentId).single()
            val remote = "$ACKNOWLEDGEMENTS/${kind.lowercase()}-$id.edi.pgp"
            sftp.writeFile(remote, "sent before the crash".encodeToByteArray())

            sendAcknowledgements().sent shouldContainExactly listOf(id)

            sftp.readFile(remote)?.decodeToString() shouldBe "sent before the crash"
            query("SELECT state, already_present FROM b2b.acknowledgement WHERE id = ?::uuid", id) {
                "${it.getString(1)} ${it.getBoolean(2)}"
            }.single() shouldBe "SENT true"
        }

        test("応答の送信: 送り直しの交換には、前の応答と同じ原本を、新しい正式名でもう一度送る") {
            val plain = orders("ICR8201")
            val first = receive(sealedByPartner(plain))
            sendAcknowledgements()
            val resent = receive(sealedByPartner(plain))
            resent.outcome shouldBe DocumentOutcome.RESENT

            sendAcknowledgements().failed.shouldBeEmpty()

            val before = acknowledgements(first.documentId).single()
            val again = acknowledgements(resent.documentId).single()
            again.first shouldNotBe before.first
            again.third shouldBe before.third
            sftp.readFile("$ACKNOWLEDGEMENTS/contrl-${again.first}.edi.pgp") shouldBe stored(before.third)
        }

        test("受け取ったすべての文書(読めない文書を含む)と送った応答が監査に 1 件ずつ記録され、ハッシュチェーンの検証が通る(DoD)") {
            val documents = query("SELECT count(*) FROM b2b.received_document") { it.getLong(1) }.single()
            val audited = query("SELECT count(*) FROM audit.audit_log WHERE action = 'b2b.document.receive'") { it.getLong(1) }.single()
            val unaudited =
                query(
                    "SELECT count(*) FROM b2b.received_document d " +
                        "WHERE NOT EXISTS (SELECT 1 FROM audit.audit_log a " +
                        "WHERE a.target_id = d.id::text AND a.payload_sha256 = d.original_sha256)",
                ) { it.getLong(1) }.single()

            val sent = query("SELECT count(*) FROM b2b.acknowledgement WHERE state = 'SENT'") { it.getLong(1) }.single()
            val sentAudited =
                query(
                    "SELECT count(*) FROM b2b.acknowledgement k JOIN audit.audit_log a ON a.target_id = k.id::text " +
                        "WHERE a.action = 'b2b.ack.send' AND a.payload_sha256 = k.original_sha256",
                ) { it.getLong(1) }.single()
            val total = query("SELECT count(*) FROM audit.audit_log") { it.getLong(1) }.single()

            (documents > 0) shouldBe true
            audited shouldBe documents
            unaudited shouldBe 0L
            (sent > 0) shouldBe true
            sentAudited shouldBe sent
            val report =
                app.connection.use { connection ->
                    connection.autoCommit = true
                    AuditVerification((ServiceName.parse("b2b-gateway") as Result.Ok).value, NoAnchors, Duration.ofDays(1)).run(connection)
                }
            report.shouldBeInstanceOf<Result.Ok<*>>()
            (report as Result.Ok).value.findings.shouldBeEmpty()
            report.value.recordCount shouldBe total
        }
    })
