@file:Suppress("MagicNumber") // テストデータの金額・件数・待ち時間、乱数のバイト数

package io.eia.order.adapters.out.persistence

import io.eia.platform.testsupport.InfraImages
import io.eia.shared.kernel.Result
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.security.SecureRandom
import java.sql.Connection
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * order の DB の統合テストの環境。ロールはローカル基盤(infra/local/postgres/init/10-service-databases.sh)と同じ構成にする。
 *
 * - 所有者 `order_service`(マイグレーション)と、アプリ用の `order_service_app`(CONNECT だけ。表の権限はマイグレーションが付ける)。
 * - テストが互いに影響しないよう、[newDatabase] でテストごとに DB を作り、所有者のロールでマイグレーションする。
 */
internal class OrderDatabaseEnvironment : AutoCloseable {
    val postgres: PostgreSQLContainer = PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
    private val ownerPassword = randomHex()
    private val appPassword = randomHex()
    private val databases = AtomicInteger()

    fun start() {
        postgres.start()
        superuser(postgres.databaseName) { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE ROLE $OWNER_ROLE LOGIN PASSWORD '$ownerPassword'")
                statement.execute(
                    "CREATE ROLE ${OrderSchema.APP_ROLE} LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION",
                )
            }
        }
    }

    /** DB を 1 つ作り、所有者のロールでマイグレーションする。 */
    fun newDatabase(): OrderDatabase {
        val name = "order_it_${databases.incrementAndGet()}"
        superuser(postgres.databaseName) { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE DATABASE $name OWNER $OWNER_ROLE")
                statement.execute("REVOKE ALL ON DATABASE $name FROM PUBLIC")
                statement.execute("GRANT CONNECT ON DATABASE $name TO ${OrderSchema.APP_ROLE}")
            }
        }
        val owner = dataSource(OWNER_ROLE, ownerPassword, name)
        val migrated = OrderSchema.migrate(owner)
        check(migrated is Result.Ok) { "マイグレーションに失敗しました: $migrated" }
        return OrderDatabase(this, name, owner, dataSource(OrderSchema.APP_ROLE, appPassword, name))
    }

    fun <T> superuser(
        database: String,
        block: (Connection) -> T,
    ): T = dataSource(postgres.username, postgres.password, database).connection.use(block)

    private fun dataSource(
        user: String,
        password: String,
        database: String,
    ): PGSimpleDataSource =
        PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl.replaceAfterLast('/', database))
            this.user = user
            this.password = password
        }

    override fun close() = postgres.stop()

    companion object {
        const val OWNER_ROLE = "order_service"

        private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))
    }
}

/** 1 つのテストの DB。[app] はアプリのロール、[owner] は所有者のロールの接続。 */
internal class OrderDatabase(
    private val environment: OrderDatabaseEnvironment,
    val name: String,
    val owner: DataSource,
    val app: DataSource,
) {
    /** アプリのロールで接続する Exposed の Database。 */
    val database: Database = Database.connect(app)

    /** スーパーユーザで、この DB に接続して [block] を呼ぶ(ロックの待ちの観察など)。 */
    fun <T> superuser(block: (Connection) -> T): T = environment.superuser(name, block)

    /** アプリのロールで SQL を 1 文実行する(権限の検査用)。 */
    fun executeAsApp(sql: String) {
        app.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
    }

    fun count(sql: String): Long =
        superuser { connection ->
            connection.createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next().let { rs.getLong(1) } } }
        }
}
