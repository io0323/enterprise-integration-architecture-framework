package io.eia.platform.audit.anchor

import io.eia.platform.audit.AuditError
import io.eia.platform.audit.jdbc.AuditLogReader
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * チェーンの先頭(末尾の記録の `seq` と `hash`)を `anchors/{service}/{date}.json`(date は UTC)に保存する(ADR-0017)。
 *
 * 定期的な保存は [AnchorCycle] が行う(保存の前に、前回のアンカーからの差分を検証する)。同じ日に何度呼んでもよい(同じキーの版として残る)。
 * 保持期限は呼ぶたびに `now + [retention]` とし、COMPLIANCE を明示する(バケットの既定の保持設定には頼らない)。
 */
public class AnchorPublisher(
    private val service: ServiceName,
    private val store: AnchorStore,
    private val retention: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(!retention.isNegative && !retention.isZero) { "retention は正の期間にしてください" }
    }

    /** 今のチェーンの末尾を保存する(検証しない)。記録が 1 件もなければ null(保存しない)。 */
    public fun publish(connection: Connection): Result<PublishedAnchor?, AuditError> =
        when (val read = AuditLogReader.readHead(connection)) {
            is Result.Ok -> read.value?.let(::publish) ?: ok(null)
            is Result.Err -> read
        }

    /** [head](検証を済ませた末尾)を保存する。 */
    public fun publish(head: AuditLogReader.Head): Result<PublishedAnchor, AuditError> {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val anchor =
            Anchor(
                service = service.value,
                seq = head.seq,
                hash = head.hash,
                canonicalVersion = head.canonicalVersion,
                createdAt = DateTimeFormatter.ISO_INSTANT.format(now),
            )
        val key = AnchorKeys.of(service, now)
        val retainUntil = now.plus(retention)
        return when (val put = store.put(key, anchor.toJson(), retainUntil)) {
            is Result.Ok -> ok(PublishedAnchor(key, put.value, anchor, retainUntil))
            is Result.Err -> put
        }
    }
}

public data class PublishedAnchor(
    val key: String,
    val versionId: String,
    val anchor: Anchor,
    val retainUntil: Instant,
)
