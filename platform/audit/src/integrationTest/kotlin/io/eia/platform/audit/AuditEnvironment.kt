package io.eia.platform.audit

import io.eia.platform.audit.anchor.S3AnchorStore
import io.eia.platform.audit.anchor.S3AnchorStoreConfig
import io.eia.platform.audit.jdbc.AuditSchema
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.testsupport.InfraImages
import io.eia.platform.testsupport.SeaweedFsContainer
import io.eia.shared.kernel.getOrNull
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.sql.Connection
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger

/**
 * 監査の統合テストの環境。PostgreSQL と SeaweedFS を、ローカル基盤(infra/local)と同じ構成にする。
 *
 * - DB: 所有者 `order_service`(マイグレーション)とアプリ用の `order_service_app`(INSERT / SELECT だけ)。
 *   改竄のテストが互いに影響しないよう、[newDatabase] でテストごとに DB を作る。
 * - S3: 管理者 `eiaf` と、`eiaf-audit` バケットだけを操作できる `eiaf-audit`。バケットポリシーは
 *   infra/local/seaweedfs/audit-bucket-policy.json をそのまま使う(compose の seaweedfs-init と同じ)。
 */
internal class AuditEnvironment : AutoCloseable {
    val postgres: PostgreSQLContainer = PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
    val seaweed =
        SeaweedFsContainer(
            mapOf(
                ADMIN to listOf("Admin", "Read", "Write", "List", "Tagging"),
                AUDIT to listOf("Read:$BUCKET", "Write:$BUCKET", "List:$BUCKET"),
            ),
        )
    private val ownerPassword = randomHex()
    private val appPassword = randomHex()
    private val databases = AtomicInteger()

    lateinit var admin: S3Client

    fun start() {
        postgres.start()
        seaweed.start()
        superuser(postgres.databaseName) { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE ROLE $OWNER_ROLE LOGIN PASSWORD '$ownerPassword'")
                statement.execute("CREATE ROLE $APP_ROLE LOGIN PASSWORD '$appPassword'")
            }
        }
        admin = s3Client(seaweed.credentials.getValue(ADMIN))
        admin.createBucket { it.bucket(BUCKET).objectLockEnabledForBucket(true) }
        admin.putBucketPolicy { it.bucket(BUCKET).policy(Files.readString(policyFile())) }
    }

    /** サービスの DB を 1 つ作り、所有者のロールでマイグレーションする。 */
    fun newDatabase(): AuditDatabase {
        val name = "order_it_${databases.incrementAndGet()}"
        superuser(postgres.databaseName) { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE DATABASE $name OWNER $OWNER_ROLE")
                statement.execute("REVOKE ALL ON DATABASE $name FROM PUBLIC")
                statement.execute("GRANT CONNECT ON DATABASE $name TO $APP_ROLE")
            }
        }
        val database = AuditDatabase(this, name, dataSource(OWNER_ROLE, ownerPassword, name), dataSource(APP_ROLE, appPassword, name))
        AuditSchema.migrate(database.owner, APP_ROLE).getOrNull() ?: error("マイグレーションに失敗しました")
        return database
    }

    fun <T> superuser(
        database: String,
        block: (Connection) -> T,
    ): T = dataSource(postgres.username, postgres.password, database).connection.use(block)

    fun anchorStore(interceptors: List<ExecutionInterceptor> = emptyList()): S3AnchorStore {
        val credentials = seaweed.credentials.getValue(AUDIT)
        val secrets =
            EnvSecretProvider(mapOf("AUDIT_S3_ACCESS_KEY" to credentials.accessKey, "AUDIT_S3_SECRET_KEY" to credentials.secretKey))
        val config = S3AnchorStoreConfig(endpoint = URI(seaweed.endpoint), bucket = BUCKET)
        return S3AnchorStore(config, S3AnchorStore.buildClient(config, secrets, interceptors))
    }

    /** audit の資格情報の S3 クライアント(権限の検査用。SDK を直接使う)。 */
    fun auditClient(): S3Client = s3Client(seaweed.credentials.getValue(AUDIT))

    private fun s3Client(credentials: SeaweedFsContainer.S3Credentials): S3Client =
        S3Client
            .builder()
            .endpointOverride(URI(seaweed.endpoint))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(credentials.accessKey, credentials.secretKey)))
            .httpClient(UrlConnectionHttpClient.create())
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .build()

    private fun dataSource(
        user: String,
        password: String,
        database: String,
    ): PGSimpleDataSource =
        PGSimpleDataSource().apply {
            setUrl("jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$database")
            this.user = user
            this.password = password
        }

    override fun close() {
        if (::admin.isInitialized) admin.close()
        seaweed.stop()
        postgres.stop()
    }

    companion object {
        const val BUCKET = "eiaf-audit"
        const val ADMIN = "eiaf"
        const val AUDIT = "eiaf-audit"
        const val OWNER_ROLE = "order_service"
        const val APP_ROLE = "order_service_app"
        private const val SECRET_BYTES = 16

        /** infra/local/seaweedfs/audit-bucket-policy.json(images.env と同じ infra/local から探す)。 */
        fun policyFile(): Path =
            Path
                .of(System.getProperty(InfraImages.FILE_PROPERTY))
                .toAbsolutePath()
                .parent
                .resolve("seaweedfs/audit-bucket-policy.json")

        private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes))
    }
}

internal class AuditDatabase(
    private val environment: AuditEnvironment,
    val name: String,
    val owner: PGSimpleDataSource,
    val app: PGSimpleDataSource,
) {
    /** アプリ用のロールで、READ COMMITTED のトランザクションの中で [block] を実行する。 */
    fun <T> appTransaction(block: (Connection) -> T): T =
        app.connection.use { connection ->
            connection.autoCommit = false
            try {
                block(connection).also { connection.commit() }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                connection.rollback()
                throw e
            }
        }

    fun <T> superuser(block: (Connection) -> T): T = environment.superuser(name, block)

    /** superuser でトリガーを外して [sql] を実行し、トリガーを戻す(改竄の再現)。 */
    fun tamper(sql: String) {
        superuser { connection ->
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE audit.audit_log DISABLE TRIGGER USER")
                statement.execute(sql)
                statement.execute("ALTER TABLE audit.audit_log ENABLE TRIGGER USER")
            }
        }
    }
}
