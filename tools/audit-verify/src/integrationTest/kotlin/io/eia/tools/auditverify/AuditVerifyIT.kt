package io.eia.tools.auditverify

import io.eia.platform.audit.Actor
import io.eia.platform.audit.ActorType
import io.eia.platform.audit.AuditEvent
import io.eia.platform.audit.AuditOutcome
import io.eia.platform.audit.AuditTarget
import io.eia.platform.audit.anchor.AnchorPublisher
import io.eia.platform.audit.anchor.S3AnchorStore
import io.eia.platform.audit.anchor.S3AnchorStoreConfig
import io.eia.platform.audit.anchor.ServiceName
import io.eia.platform.audit.jdbc.AuditLog
import io.eia.platform.audit.jdbc.AuditSchema
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.testsupport.InfraImages
import io.eia.platform.testsupport.SeaweedFsContainer
import io.eia.shared.kernel.getOrNull
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.sql.Connection
import java.time.Duration
import java.time.Instant

private const val BUCKET = "eiaf-audit"
private const val RECORDS = 5
private const val RETENTION_MINUTES = 3L
private val RETENTION: Duration = Duration.ofMinutes(RETENTION_MINUTES)

/**
 * `make audit-verify` の本体を、実際の PostgreSQL と SeaweedFS に対して実行し、終了コードを確かめる。
 * ロールの分離とバケットポリシーは platform/audit の統合テストで確かめるため、ここでは DB の既定のユーザーと管理者の資格情報を使う。
 */
class AuditVerifyIT :
    FunSpec({
        val postgres = PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
        val seaweed = SeaweedFsContainer(mapOf("eiaf" to listOf("Admin", "Read", "Write", "List", "Tagging")))
        val service = ServiceName.parse("order").getOrNull()!!
        lateinit var dataSource: PGSimpleDataSource

        fun env(): Map<String, String> =
            mapOf(
                "AUDIT_SERVICE" to service.value,
                "AUDIT_JDBC_URL" to postgres.jdbcUrl,
                "AUDIT_DB_USER" to postgres.username,
                "AUDIT_S3_ENDPOINT" to seaweed.endpoint,
                "AUDIT_MIN_RETENTION" to "PT2M",
            )

        fun secrets(secretKey: String = seaweed.credentials.getValue("eiaf").secretKey): EnvSecretProvider =
            EnvSecretProvider(
                mapOf(
                    "AUDIT_DB_PASSWORD" to postgres.password,
                    "AUDIT_VERIFY_S3_ACCESS_KEY" to seaweed.credentials.getValue("eiaf").accessKey,
                    "AUDIT_VERIFY_S3_SECRET_KEY" to secretKey,
                ),
            )

        fun verify(secrets: EnvSecretProvider = secrets()): Pair<Int, String> {
            val buffer = ByteArrayOutputStream()
            val code = AuditVerifyCommand(secrets).run(env(), PrintStream(buffer, true, Charsets.UTF_8))
            return code to buffer.toString(Charsets.UTF_8)
        }

        fun <T> transaction(block: (Connection) -> T): T =
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                block(connection).also { connection.commit() }
            }

        beforeSpec {
            postgres.start()
            seaweed.start()
            dataSource =
                PGSimpleDataSource().apply {
                    setUrl(postgres.jdbcUrl)
                    user = postgres.username
                    password = postgres.password
                }
            AuditSchema.migrate(dataSource, postgres.username).getOrNull().shouldNotBeNull()
            val credentials = seaweed.credentials.getValue("eiaf")
            S3Client
                .builder()
                .endpointOverride(URI(seaweed.endpoint))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(credentials.accessKey, credentials.secretKey)),
                ).httpClient(UrlConnectionHttpClient.create())
                .build()
                .use { admin -> admin.createBucket { it.bucket(BUCKET).objectLockEnabledForBucket(true) } }

            val log = AuditLog()
            repeat(RECORDS) { n ->
                val event =
                    AuditEvent(
                        Instant.now(),
                        Actor(ActorType.SERVICE, "order-service"),
                        "order.create",
                        AuditTarget("order", "ord-$n"),
                        AuditOutcome.SUCCESS,
                    )
                transaction { log.append(it, event).getOrNull().shouldNotBeNull() }
            }
            val config =
                S3AnchorStoreConfig(URI(seaweed.endpoint), BUCKET, AuditVerifyConfig.S3_ACCESS_KEY, AuditVerifyConfig.S3_SECRET_KEY)
            S3AnchorStore(config, secrets()).use { store ->
                dataSource.connection.use { AnchorPublisher(service, store, RETENTION).publish(it).getOrNull().shouldNotBeNull() }
            }
        }
        afterSpec {
            seaweed.stop()
            postgres.stop()
        }

        test("改竄がなければ終了コード 0") {
            val (code, output) = verify()
            code shouldBe AuditVerifyCommand.INTACT
            output shouldContain "records=$RECORDS head_seq=$RECORDS anchor_versions=1"
        }

        test("S3 の資格情報が誤っていれば終了コード 2") {
            val (code, output) = verify(secrets(secretKey = "wrong"))
            code shouldBe AuditVerifyCommand.FAILED
            output shouldContain "ERROR"
        }

        test("アンカーより後の記録を末尾から削除すると終了コード 1") {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("ALTER TABLE audit.audit_log DISABLE TRIGGER USER")
                    statement.execute("DELETE FROM audit.audit_log WHERE seq = $RECORDS")
                    statement.execute("ALTER TABLE audit.audit_log ENABLE TRIGGER USER")
                }
            }
            val (code, output) = verify()
            code shouldBe AuditVerifyCommand.TAMPERED
            output shouldContain "NG anchor_record_missing"
        }
    })
