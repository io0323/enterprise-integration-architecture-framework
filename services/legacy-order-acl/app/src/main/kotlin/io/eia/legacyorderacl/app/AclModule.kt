package io.eia.legacyorderacl.app

import io.eia.legacyorderacl.adapters.inbound.AclMetrics
import io.eia.legacyorderacl.adapters.inbound.LegacyChangeHandler
import io.eia.legacyorderacl.adapters.outbound.KafkaLegacyOrderStatePublisher
import io.eia.legacyorderacl.adapters.outbound.LegacyOrderEventSchemas
import io.eia.legacyorderacl.adapters.reconcile.JdbcLegacySource
import io.eia.legacyorderacl.adapters.reconcile.JdbcSnapshotRequests
import io.eia.legacyorderacl.adapters.reconcile.KafkaPublishedLegacyOrders
import io.eia.legacyorderacl.adapters.reconcile.KafkaReconcileTombstones
import io.eia.legacyorderacl.adapters.reconcile.PublishedReaderSettings
import io.eia.legacyorderacl.adapters.reconcile.ReconcileMetrics
import io.eia.legacyorderacl.adapters.reconcile.ReconcileWaits
import io.eia.legacyorderacl.adapters.reconcile.Sha256Fingerprints
import io.eia.legacyorderacl.application.port.inbound.ReconcileLegacyOrdersUseCase
import io.eia.legacyorderacl.application.port.inbound.ResyncLegacyOrdersUseCase
import io.eia.legacyorderacl.application.port.inbound.TranslateLegacyOrderChangeUseCase
import io.eia.legacyorderacl.application.port.outbound.LegacyOrderStatePublisher
import io.eia.legacyorderacl.application.usecase.ReconcileLegacyOrdersService
import io.eia.legacyorderacl.application.usecase.ResyncLegacyOrdersService
import io.eia.legacyorderacl.application.usecase.TranslateLegacyOrderChangeService
import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.messagingkafka.EventConsumer
import io.eia.platform.messagingkafka.EventProducer
import io.eia.platform.messagingkafka.KafkaProducerSettings
import io.eia.platform.messagingkafka.OffsetCommitter
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.platform.security.secret.Secret
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.delay
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.koin.dsl.module
import org.postgresql.ds.PGSimpleDataSource

/**
 * legacy-order-acl の配線(Koin)。
 * 書き込むイベントのスキーマ ID は起動時に [SchemaIdBook] で解決し(ADR-0025 §3)、生の CDC の書き手のスキーマは contentId から取る
 * ([WriterSchemas]。新しい contentId を初めて見たときだけ問い合わせ、キャッシュする。ADR-0026 §4)。
 *
 * @param committer オフセットのコミット(統合テストで、発行の後・コミットの前に止める場合だけ差し替える)
 */
internal fun aclModule(
    config: AclConfig,
    runtime: ObservabilityRuntime,
    committer: OffsetCommitter?,
    reconcileSecrets: ReconcileSecrets? = null,
) = module {
    single { runtime }
    single { HttpClient(CIO) }
    single { ApicurioRegistryClient(SchemaRegistryConfig(config.schemaRegistryUrl), get()) }
    single { SchemaIdBook(LegacyOrderEventSchemas.subjects, get()) }
    single { WriterSchemas(get()) }
    // 出力と DLQ で 1 つの Producer を共有する(冪等・acks=all。ADR-0026 §5)
    single<Producer<ByteArray, ByteArray>> {
        KafkaProducer(KafkaProducerSettings(config.bootstrapServers, clientId = "legacy-order-acl").toProperties())
    }
    single { EventProducer(get(), runtime, KafkaLegacyOrderStatePublisher.SOURCE) }
    single { DeadLetterPublisher(get()) }
    single<LegacyOrderStatePublisher> { KafkaLegacyOrderStatePublisher(get(), get()) }
    single<TranslateLegacyOrderChangeUseCase> { TranslateLegacyOrderChangeService(get()) }
    single { AclMetrics(runtime.meter) }
    single { LegacyChangeHandler(get(), get()) }
    // 生の CDC の読み取りは共通の Consumer(外部のトピックの購読。ADR-0028 改訂履歴・#88)
    single {
        val consumer =
            KafkaConsumer<ByteArray?, ByteArray?>(
                EventConsumer.consumerProperties(config.bootstrapServers, config.groupId, LegacyChangeHandler.CLIENT_ID),
            )
        val subscriptions = listOf(get<LegacyChangeHandler>().subscription(get()))
        if (committer == null) {
            EventConsumer(consumer, config.groupId, subscriptions, get(), runtime)
        } else {
            EventConsumer(consumer, config.groupId, subscriptions, get(), runtime, committer = committer)
        }
    }
    config.reconcile?.let { reconcile -> includes(reconcileModule(config, reconcile, runtime, reconcileSecrets)) }
}

/**
 * 照合(ADR-0027)の配線。レガシーの DB は読み取り専用のロール(`eiaf_reconcile`)で、接続はプールせず、使うたびに開いて閉じる
 * (ロールの接続数の上限は 4。照合は 15 分ごとなので、プールで接続を持ち続けない)。
 */
private fun reconcileModule(
    config: AclConfig,
    reconcile: ReconcileConfig,
    runtime: ObservabilityRuntime,
    secrets: ReconcileSecrets?,
) = module {
    val waits = ReconcileWaits(timeout = reconcile.waitTimeout)

    fun dataSource(
        url: String,
        user: String = reconcile.user,
        password: Secret? = secrets?.reconcile,
    ) = PGSimpleDataSource().apply {
        setURL(url)
        this.user = user
        this.password = requireNotNull(password) { "$user のパスワードが必要です" }.reveal()
    }
    single<Admin> { Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to config.bootstrapServers)) }
    single { JdbcLegacySource(dataSource(reconcile.dbUrl), dataSource(reconcile.slotDbUrl), waits = waits) }
    single {
        KafkaPublishedLegacyOrders(
            admin = get(),
            consumers = { KafkaConsumer(PublishedReaderSettings.properties(config.bootstrapServers)) },
            writerSchemas = get(),
            group = config.groupId,
            waits = waits,
        )
    }
    single<ReconcileLegacyOrdersUseCase> {
        ReconcileLegacyOrdersService(
            source = get<JdbcLegacySource>(),
            published = get<KafkaPublishedLegacyOrders>(),
            fingerprints = Sha256Fingerprints,
            pause = { delay(it) },
            recheckAfter = reconcile.recheckAfter,
        )
    }
    single { ReconcileMetrics(runtime.meter, reconcile.interval) }
    // 自動の再同期(ADR-0027 §6)。signal 表はプライマリにだけ書ける(スロットと同じ接続先)。照合の tombstone は ce_source で見分ける
    if (reconcile.autoResync) {
        single<ResyncLegacyOrdersUseCase> {
            ResyncLegacyOrdersService(
                source = get<JdbcLegacySource>(),
                snapshots = JdbcSnapshotRequests(dataSource(reconcile.slotDbUrl, reconcile.resyncUser, secrets?.resync)),
                tombstones = KafkaReconcileTombstones(EventProducer(get(), runtime, KafkaReconcileTombstones.SOURCE)),
                limit = reconcile.resyncLimit,
            )
        }
    }
    single { ReconcileJob(get(), get(), runtime, reconcile.interval, getOrNull()) }
}
