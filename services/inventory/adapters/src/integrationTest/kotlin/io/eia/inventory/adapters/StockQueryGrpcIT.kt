@file:Suppress("MagicNumber") // 乱数のバイト数・待ち時間

package io.eia.inventory.adapters

import io.eia.inventory.adapters.inbound.grpc.StockQueryGrpcService
import io.eia.inventory.adapters.out.persistence.InventorySchema
import io.eia.inventory.adapters.out.persistence.JdbcStockQueries
import io.eia.inventory.application.usecase.GetStockLevelsService
import io.eia.platform.grpc.GrpcCallRejected
import io.eia.platform.grpc.GrpcCallUnavailable
import io.eia.platform.grpc.GrpcChannels
import io.eia.platform.grpc.GrpcClient
import io.eia.platform.grpc.GrpcServer
import io.eia.platform.grpc.GrpcServerConfig
import io.eia.platform.grpc.GrpcService
import io.eia.platform.grpc.GrpcTelemetry
import io.eia.platform.grpc.GrpcTokenSource
import io.eia.platform.grpcstubs.inventory.v1.BatchGetStockRequest
import io.eia.platform.grpcstubs.inventory.v1.BatchGetStockResponse
import io.eia.platform.grpcstubs.inventory.v1.StockQueryServiceGrpc
import io.eia.platform.grpcstubs.inventory.v1.StockQueryServiceGrpcKt.StockQueryServiceCoroutineStub
import io.eia.platform.security.jwt.VerifiedToken
import io.eia.platform.security.tls.ServerTls
import io.eia.platform.security.tls.TlsFiles
import io.eia.platform.security.tls.TlsVariables
import io.eia.platform.testsupport.InfraImages
import io.eia.platform.testsupport.TestPki
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.CallDeadline
import io.eia.shared.sdk.http.token.AccessToken
import io.grpc.Status
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.security.SecureRandom
import java.util.HexFormat
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private fun <T> Result<T, *>.value(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private fun hex(): String = HexFormat.of().formatHex(ByteArray(16).also { SecureRandom().nextBytes(it) })

/**
 * 在庫の照会の gRPC を、実際の PostgreSQL と mTLS で確かめる(INT-INVENTORY-003。ADR-0042 §4・§6)。トークンの実際の検証は InventoryAppIT。
 * 締め切りの確認では、在庫の表を排他ロックして問い合わせを待たせ、打ち切られた問い合わせが DB に残らないことを確かめる。
 */
class StockQueryGrpcIT :
    FunSpec({
        val owner = "inventory_service"
        val ownerPassword = hex()
        val appPassword = hex()
        val postgres =
            PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
                .withCommand("postgres", "-c", "wal_level=logical")
                .also { it.start() }

        fun dataSource(
            user: String,
            password: String,
        ) = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl.replaceAfterLast('/', "inventory_service"))
            this.user = user
            this.password = password
        }

        val superuser =
            PGSimpleDataSource().apply {
                setURL(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
        superuser.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE ROLE $owner LOGIN PASSWORD '$ownerPassword'")
                statement.execute(
                    "CREATE ROLE ${InventorySchema.APP_ROLE} LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE",
                )
                statement.execute("CREATE ROLE ${InventorySchema.CDC_ROLE} LOGIN REPLICATION PASSWORD '${hex()}'")
                statement.execute("CREATE DATABASE inventory_service OWNER $owner")
            }
        }
        InventorySchema.migrate(dataSource(owner, ownerPassword)).value()

        val database = Database.connect(dataSource(InventorySchema.APP_ROLE, appPassword))
        val pki = TestPki()
        val telemetry = OpenTelemetry.noop().let { GrpcTelemetry(it, it.getTracer("test"), it.getMeter("test")) }

        fun tls(cert: TestPki.Issued): ServerTls =
            ServerTls
                .load(TlsFiles(TlsVariables("TEST"), cert.certFile.toString(), cert.keyFile.toString(), pki.caFile.toString()))
                .value()

        fun server(maxBudget: Duration): GrpcServer =
            GrpcServer.start(
                GrpcServerConfig(
                    port = 0,
                    tls = tls(pki.issue("inventory-service", listOf("localhost"))),
                    allowedClients = setOf("bff-graphql"),
                    verify = {
                        ok(
                            VerifiedToken(
                                "iss",
                                "sub",
                                "client",
                                listOf("aud"),
                                setOf("inventory.stock:read"),
                                Clock.System.now(),
                                Clock.System.now() + 1.hours,
                                null,
                            ),
                        )
                    },
                    telemetry = telemetry,
                    services =
                        listOf(
                            GrpcService(
                                StockQueryGrpcService(GetStockLevelsService(JdbcStockQueries(database))),
                                setOf("inventory.stock:read"),
                            ),
                        ),
                    maxBudget = maxBudget,
                ),
            )

        val tokens =
            object : GrpcTokenSource {
                override suspend fun token(): Result<AccessToken, DomainError> = ok(AccessToken("token", null, emptySet()))

                override suspend fun invalidate(token: AccessToken) = Unit
            }
        val client = GrpcClient("inventory", tokens, telemetry, fallbackDeadline = 30.seconds)
        val clientTls = tls(pki.issue("bff", listOf("bff-graphql")))

        suspend fun query(
            server: GrpcServer,
            request: BatchGetStockRequest,
        ): Result<BatchGetStockResponse, DomainError> {
            val channel = GrpcChannels.mutualTls("localhost:${server.port}", clientTls)
            return try {
                client.call(StockQueryServiceCoroutineStub(channel), StockQueryServiceGrpc.getBatchGetStockMethod()) { metadata ->
                    batchGetStock(request, metadata)
                }
            } finally {
                channel.shutdownNow()
            }
        }

        fun request(skus: List<String>) = BatchGetStockRequest.newBuilder().addAllSkus(skus).build()

        fun waitingQueries(): Long =
            dataSource(owner, ownerPassword).connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT count(*) FROM pg_stat_activity WHERE usename = '${InventorySchema.APP_ROLE}' AND state = 'active'",
                        ).use { rows ->
                            rows.next()
                            rows.getLong(1)
                        }
                }
            }

        /** 在庫の表を排他ロックした間に [block] を実行し、問い合わせを待たせる。 */
        suspend fun <T> withStockLocked(block: suspend () -> T): T =
            dataSource(owner, ownerPassword).connection.use { connection ->
                connection.autoCommit = false
                connection.createStatement().use { it.execute("LOCK TABLE stock IN ACCESS EXCLUSIVE MODE") }
                try {
                    block()
                } finally {
                    connection.rollback()
                }
            }

        suspend fun noWaitingQueryRemains() {
            val deadline = TimeSource.Monotonic.markNow() + 5.seconds
            while (waitingQueries() != 0L) {
                check(deadline.hasNotPassedNow()) { "打ち切った問い合わせが DB に残っています" }
                delay(100.milliseconds)
            }
        }

        val running = server(maxBudget = 30.seconds)
        afterSpec {
            running.stop(0.seconds)
            pki.close()
            postgres.stop()
        }

        test("引当できる数(手持ち - 引当済み)と時刻を読む。ない SKU は unknown_skus に返す") {
            dataSource(owner, ownerPassword).connection.use { connection ->
                connection.createStatement().use { it.execute("UPDATE stock SET reserved = 4 WHERE sku = 'SKU-LIMITED'") }
            }

            val response = query(running, request(listOf("SKU-LIMITED", "SKU-SOLDOUT", "SKU-X", "SKU-X"))).value()

            response.levelsList.associate { it.sku to it.available } shouldBe mapOf("SKU-LIMITED" to 6L, "SKU-SOLDOUT" to 0L)
            response.levelsList.all { it.updatedAt.seconds > 0 } shouldBe true
            response.unknownSkusList shouldBe listOf("SKU-X")
        }

        test("0 件と上限を超える件数は INVALID_ARGUMENT") {
            query(running, request(emptyList())) shouldBe err(GrpcCallRejected("inventory", Status.Code.INVALID_ARGUMENT))
            val tooMany = request(List(501) { "SKU-$it" })
            query(running, tooMany) shouldBe err(GrpcCallRejected("inventory", Status.Code.INVALID_ARGUMENT))
        }

        test("呼び出し元の deadline で打ち切り、DB の問い合わせも残らない") {
            withStockLocked {
                val started = TimeSource.Monotonic.markNow()

                val result = withContext(CallDeadline.after(300.milliseconds)) { query(running, request(listOf("SKU-1"))) }

                result shouldBe err(GrpcCallUnavailable("inventory", Status.Code.DEADLINE_EXCEEDED))
                started.elapsedNow() shouldBeLessThan 5.seconds
                // statement_timeout(残り時間 + 0.5 秒)で切れる。ロックを持ったまま確かめる
                noWaitingQueryRemains()
            }
        }

        test("呼び出し元の deadline がなくても、サーバの上限で打ち切り、DB の問い合わせも残らない") {
            val limited = server(maxBudget = 300.milliseconds)
            withStockLocked {
                val started = TimeSource.Monotonic.markNow()

                val result = query(limited, request(listOf("SKU-1")))

                result shouldBe err(GrpcCallUnavailable("inventory", Status.Code.DEADLINE_EXCEEDED))
                started.elapsedNow() shouldBeLessThan 5.seconds
                noWaitingQueryRemains()
            }
            limited.stop(0.seconds)
        }
    })
