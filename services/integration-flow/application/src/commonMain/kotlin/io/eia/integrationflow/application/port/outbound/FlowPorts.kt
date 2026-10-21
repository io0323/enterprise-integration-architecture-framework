package io.eia.integrationflow.application.port.outbound

import io.eia.integrationflow.application.port.inbound.TransformedRecord
import io.eia.integrationflow.domain.FlowDefinition
import io.eia.integrationflow.domain.SourceValue
import io.eia.integrationflow.domain.VersionDecision
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlin.time.Instant

/** 業務の更新の範囲(トランザクション)。[block] が `Ok` を返せば確定し、`Err` を返すか例外を投げれば取り消す。 */
public interface TransactionRunner {
    public suspend fun <T> inTransaction(block: suspend () -> Result<T, DomainError>): Result<T, DomainError>
}

/**
 * 新旧の判定の記録(Flow とキーごとの最新の版。ADR-0036 §3)。**Webhook の経路と定期の同期の経路が同じ記録を使う**(ADR-0035 §4)。
 * 発行(Outbox)と同じトランザクションの中で呼ぶ。同じキーの同時の更新では、片方が確定を待つ。
 */
public interface FlowVersions {
    /** [version] が記録より新しければ記録を進めて [VersionDecision.NEWER]。同じなら SAME、古ければ STALE(記録は変えない)。 */
    public suspend fun advance(
        flowId: String,
        key: String,
        version: Long,
    ): Result<VersionDecision, DomainError>
}

/** 冪等消費の記録(platform/inbox の processed_message。ADR-0028 §3)。業務の更新と同じトランザクションの中で呼ぶ。 */
public interface ProcessedMessages {
    /** 初めてなら記録して true、処理済みなら false。 */
    public suspend fun markProcessed(
        messageId: String,
        topic: String,
    ): Result<Boolean, DomainError>
}

/**
 * 写した結果を Canonical Model のイベントにして、届け先のトピックに Outbox で出す(ADR-0007)。
 * Canonical の型への変換と検証は adapters が行う(ADR-0010 Decision 7)。新旧の判定の記録と同じトランザクションの中で呼ぶ。
 */
public interface CanonicalPublisher {
    public suspend fun publish(
        flow: FlowDefinition,
        record: TransformedRecord,
        sourceSystem: String,
    ): Result<Unit, DomainError>
}

/**
 * SaaS の API の Connector(Flow の poll の trigger。ADR-0035 §6)。ページング・Rate Limit(429 と Retry-After)・Resilience を内側で扱う。
 *
 * @property connector カタログの連携 ID(例 `INT-SALES-006`)
 * @property sourceSystem 発行するイベントの元の SaaS の名前(例 `crm`)
 */
public interface SaasConnector {
    public val connector: String
    public val sourceSystem: String
    public val resources: Set<String>

    /** [resource] の、[updatedSince] の後に変わったレコードの 1 ページ([cursor] は前のページの続き)。 */
    public suspend fun page(
        resource: String,
        updatedSince: Instant?,
        cursor: String?,
    ): Result<SourcePage, DomainError>
}

/** 1 ページのレコードと、次のページのカーソル(なければ null)。 */
public class SourcePage(
    public val records: List<SourceValue>,
    public val nextCursor: String?,
)

/** 定期の同期の Watermark(前の同期を始めた時刻。Flow と Connector ごと。Framework 7 の Checkpoint)。 */
public interface SyncWatermarks {
    public suspend fun get(
        flowId: String,
        connector: String,
    ): Result<Instant?, DomainError>

    public suspend fun set(
        flowId: String,
        connector: String,
        watermark: Instant,
    ): Result<Unit, DomainError>
}
