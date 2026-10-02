package io.eia.platform.schemaregistry

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok
import io.eia.shared.kernel.onOk
import java.util.concurrent.ConcurrentHashMap

/**
 * 受信したメッセージの contentId から、書き手のスキーマを取る(受信側。ADR-0025 §1)。
 * contentId の内容は不変なので、一度取得したものは期限なしでキャッシュする。失敗はキャッシュしない(次のメッセージで取り直す)。
 */
public class WriterSchemas(
    private val client: ApicurioRegistryClient,
) {
    private val cache = ConcurrentHashMap<ContentId, String>()

    public suspend fun schemaOf(contentId: ContentId): Result<String, SchemaRegistryError> =
        cache[contentId]?.let { ok(it) } ?: client.schemaOf(contentId).onOk { cache.putIfAbsent(contentId, it) }
}
