package io.eia.platform.schemaregistry

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * サービスが書き込みに使うスキーマの contentId を、起動時にすべて解決して持つ(ADR-0025 §3)。
 *
 * - 注文 API などの可用性を Schema Registry に引きずられないよう、リクエストの処理中はレジストリに問い合わせない。
 *   [contentIdOf] はメモリだけを見る。解決済みの ID は内容に対して不変なので、期限を設けずに持ち続ける。
 * - すべての ID が解決するまで [isReady] は false。サービスは `/health/ready` をこれで失敗させ、トラフィックを受けない。
 * - 起動時にレジストリが止まっていても、サービスは起動して [resolveUntilReady] で解決を繰り返す。解決した後は、
 *   レジストリが止まってもイベントの書き込み(Outbox への保存)は続けられる。
 * - 未登録([SchemaNotRegistered])も繰り返す(`make schemas` を後から実行すれば回復する)。ログには理由を残す。
 *
 * @param subjects サービスが書き込むトピックとスキーマ。トピックは重複させない(1 トピックに書き手のスキーマは 1 つ)
 */
public class SchemaIdBook(
    subjects: Collection<SchemaSubject>,
    private val client: ApicurioRegistryClient,
) {
    private val subjects: Map<String, SchemaSubject> = subjects.associateBy { it.topic }

    init {
        require(this.subjects.size == subjects.size) { "トピックが重複しています: ${subjects.groupBy { it.topic }.filterValues { it.size > 1 }.keys}" }
    }

    @Volatile
    private var resolved: Map<String, ContentId> = emptyMap()

    /** すべてのスキーマの ID を解決し終えたか。 */
    public val isReady: Boolean get() = resolved.size == subjects.size

    /** 解決していないトピック(`/health/ready` の詳細やログに使う)。 */
    public val pendingTopics: Set<String> get() = subjects.keys - resolved.keys

    /** [subject] の contentId。レジストリには問い合わせない。未解決、または登録したものと違うスキーマなら Err。 */
    public fun contentIdOf(subject: SchemaSubject): Result<ContentId, SchemaRegistryError> {
        require(subjects[subject.topic] == subject) { "${subject.topic} のスキーマは SchemaIdBook に登録したものと違います" }
        return resolved[subject.topic]?.let { ok(it) } ?: err(SchemaIdsNotResolved(subject.topic))
    }

    /**
     * 未解決のスキーマを 1 回ずつ解決する。解決できたものは残し、最初の失敗を返す(残りも試す)。
     * すべて解決済みなら、レジストリに問い合わせずに Ok を返す。
     */
    public suspend fun resolve(): Result<Unit, SchemaRegistryError> {
        var firstFailure: SchemaRegistryError? = null
        subjects.values
            .filter { it.topic !in resolved }
            .forEach { subject ->
                when (val found = client.findContentId(subject)) {
                    is Result.Ok -> {
                        resolved = resolved + (subject.topic to found.value)
                        logger.info("スキーマの ID を解決しました topic={} contentId={}", subject.topic, found.value)
                    }

                    is Result.Err -> {
                        firstFailure = firstFailure ?: found.error
                        logger.warn("スキーマの ID を解決できません topic={} code={} {}", subject.topic, found.error.code, found.error.message)
                    }
                }
            }
        return firstFailure?.let { err(it) } ?: ok(Unit)
    }

    /** すべて解決するまで [retryInterval] ごとに [resolve] を繰り返す。キャンセルされれば止まる(サービスの停止時)。 */
    public suspend fun resolveUntilReady(retryInterval: Duration = DEFAULT_RETRY_INTERVAL) {
        require(retryInterval.isPositive()) { "retryInterval は正の値にしてください" }
        while (resolve() is Result.Err) {
            delay(retryInterval)
        }
    }

    public companion object {
        public val DEFAULT_RETRY_INTERVAL: Duration = 5.seconds
        private val logger = LoggerFactory.getLogger(SchemaIdBook::class.java)
    }
}
