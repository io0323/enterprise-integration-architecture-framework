package io.eia.platform.audit.anchor

import io.eia.platform.audit.AuditError
import io.eia.platform.audit.jdbc.AuditLogReader
import io.eia.platform.audit.jdbc.inReadOnlySnapshot
import io.eia.platform.audit.verify.ChainVerifier
import io.eia.platform.audit.verify.Finding
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
import java.sql.Connection
import java.time.Clock
import java.time.Instant

/**
 * アンカーの定期的な保存の 1 回分(ADR-0017 §5)。サービスのスケジューラが間隔ごとに [run] を呼ぶ。
 *
 * 1. 起点は前回のアンカー。プロセスで最初の回は、ストレージで最後に保存された版を読む(なければチェーンの先頭)。
 * 2. 1 つのスナップショットで末尾を読む。
 *    - 記録がない → [AnchorOutcome.Empty]
 *    - 末尾が前回のアンカーと同じ(記録が増えていない)→ [AnchorOutcome.Unchanged]。保存しない
 *    - それ以外 → 前回のアンカーの直後から末尾までを検証する。改竄の疑いがなければ末尾を保存する([AnchorOutcome.Published])。
 *      あれば保存しない([AnchorOutcome.Rejected])。改竄された状態をアンカーとして固定しないため
 * 3. 保存はスナップショットの外で行う(S3 への書込みの間、トランザクションを開けたままにしない)。
 *
 * **保存の前の検証は差分だけ**。前回のアンカーより前の記録の改竄は、`make audit-verify` の定期的な全体の検証で見つける
 * (ADR-0017 §6)。差分だけにするのは、間隔ごとの処理の時間をチェーンの長さによらず一定にするため。
 *
 * [connection] は検査専用の接続で、自動コミットが有効であること。
 */
public class AnchorCycle(
    private val service: ServiceName,
    private val store: AnchorStore,
    private val publisher: AnchorPublisher,
    private val listener: AnchorCycleListener = AnchorCycleListener.NONE,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var baseline: Baseline? = null
    private var loaded = false

    /** 前回のアンカー(検証の起点)。 */
    private data class Baseline(
        val key: String,
        val versionId: String,
        val position: AuditLogReader.Position,
    )

    /** 1 回分の検査と保存。ストレージや DB に届かないときは Err(次の回でやり直す)。結果は [listener] にも渡す。 */
    @Synchronized
    public fun run(connection: Connection): Result<AnchorOutcome, AuditError> {
        val result = check(connection)
        val at = clock.instant()
        when (result) {
            is Result.Ok -> listener.checked(result.value, at)
            is Result.Err -> listener.failed(result.error, at)
        }
        return result
    }

    @Suppress("ReturnCount") // 起点の読み込み・スナップショットの読み込みのそれぞれの失敗で返す
    private fun check(connection: Connection): Result<AnchorOutcome, AuditError> {
        if (!loaded) {
            when (val restored = restore()) {
                is Result.Ok -> restored.value?.let { return ok(AnchorOutcome.Rejected(listOf(it))) }
                is Result.Err -> return restored
            }
        }
        val start = baseline
        val inspection =
            when (val inspected = inReadOnlySnapshot(connection) { inspect(connection, start) }) {
                is Result.Ok -> inspected.value
                is Result.Err -> return inspected
            }
        return when (inspection) {
            is Inspection.Decided -> {
                ok(inspection.outcome)
            }

            is Inspection.Verified -> {
                publisher.publish(inspection.head).map { published ->
                    baseline = Baseline(published.key, published.versionId, inspection.head.position)
                    AnchorOutcome.Published(published, inspection.records)
                }
            }
        }
    }

    /** ストレージで最後に保存された版を起点にする。版が解釈できなければ、その理由を返す(起点にしない)。 */
    private fun restore(): Result<Finding?, AuditError> =
        when (val latest = store.latest(AnchorKeys.prefix(service))) {
            is Result.Err -> {
                latest
            }

            is Result.Ok -> {
                val version = latest.value
                val anchor = version?.body?.let(Anchor::parse)
                when {
                    version == null -> {
                        loaded = true
                        ok(null)
                    }

                    anchor is Result.Ok && anchor.value.service == service.value -> {
                        baseline = Baseline(version.key, version.versionId, AuditLogReader.Position(anchor.value.seq, anchor.value.hash))
                        loaded = true
                        listener.restored(anchor.value)
                        ok(null)
                    }

                    else -> {
                        // 次の回もストレージから読み直す(起点がないまま保存すると、検証していない記録を固定するため)
                        val reason = (anchor as? Result.Err)?.error ?: version.readError ?: "アンカーのサービス名がキーと一致しません"
                        ok(Finding.AnchorInvalid(version.key, version.versionId, reason))
                    }
                }
            }
        }

    private sealed interface Inspection {
        data class Decided(
            val outcome: AnchorOutcome,
        ) : Inspection

        /** [start] の直後から [head] まで、改竄の疑いなく検証できた。 */
        data class Verified(
            val head: AuditLogReader.Head,
            val records: Long,
        ) : Inspection
    }

    @Suppress("ReturnCount") // 読み込みの失敗・検証せずに決まる場合に返す
    private fun inspect(
        connection: Connection,
        start: Baseline?,
    ): Result<Inspection, AuditError> {
        val head =
            when (val read = AuditLogReader.readHead(connection)) {
                is Result.Ok -> read.value
                is Result.Err -> return read
            }
        decideWithoutReading(head, start)?.let { return ok(Inspection.Decided(it)) }
        if (head == null) return ok(Inspection.Decided(AnchorOutcome.Empty))
        val verifier = ChainVerifier(start = start?.position)
        when (val read = AuditLogReader.forEachRow(connection, after = start?.position, consumer = verifier::accept)) {
            is Result.Ok -> Unit
            is Result.Err -> return read
        }
        val chain = verifier.result()
        val reachedHead = chain.headSeq == head.seq && chain.lastHash == head.hash
        val findings = chain.findings + if (reachedHead) emptyList() else listOf(Finding.HeadNotReached(head.seq, chain.headSeq))
        return ok(if (findings.isEmpty()) Inspection.Verified(head, chain.count) else Inspection.Decided(AnchorOutcome.Rejected(findings)))
    }

    /** 末尾と前回のアンカーだけで決まる結果(記録がない・増えていない・末尾が前回のアンカーより前に戻った)。差分を読むなら null。 */
    private fun decideWithoutReading(
        head: AuditLogReader.Head?,
        start: Baseline?,
    ): AnchorOutcome? {
        if (start == null) return if (head == null) AnchorOutcome.Empty else null
        val anchored = start.position.seq

        fun rejected(finding: Finding) = AnchorOutcome.Rejected(listOf(finding))
        return when {
            head == null -> rejected(Finding.AnchorRecordMissing(start.key, start.versionId, anchored, null))

            head.position == start.position -> AnchorOutcome.Unchanged(head.seq)

            // 末尾からの削除・前回のアンカーの記録の書き換え
            head.seq < anchored -> rejected(Finding.AnchorRecordMissing(start.key, start.versionId, anchored, head.seq))

            head.seq == anchored -> rejected(Finding.AnchorHashMismatch(start.key, start.versionId, anchored))

            else -> null
        }
    }
}

/** [AnchorCycle.run] の 1 回分の結果。[Empty]・[Unchanged]・[Published] は検査の成功(監視の対象。ADR-0017 §8)。 */
public sealed interface AnchorOutcome {
    /** メトリクスの属性 `outcome` の値。 */
    public val label: String

    public val succeeded: Boolean get() = this !is Rejected

    /** 記録がない(保存しない)。 */
    public data object Empty : AnchorOutcome {
        override val label: String get() = "empty"
    }

    /** 前回のアンカーから記録が増えていない(保存しない)。 */
    public data class Unchanged(
        val seq: Long,
    ) : AnchorOutcome {
        override val label: String get() = "unchanged"
    }

    /** 差分の [verifiedRecords] 件を検証し、末尾を保存した。 */
    public data class Published(
        val anchor: PublishedAnchor,
        val verifiedRecords: Long,
    ) : AnchorOutcome {
        override val label: String get() = "published"
    }

    /** 改竄の疑いがあるため保存しなかった。 */
    public data class Rejected(
        val findings: List<Finding>,
    ) : AnchorOutcome {
        override val label: String get() = "rejected"
    }
}

/** [AnchorCycle] の結果を受け取る。メトリクス([AnchorMetrics])に使う。 */
public interface AnchorCycleListener {
    /** 検査を終えた([outcome] が改竄の疑いを含む場合も)。[at] は検査を終えた時刻。 */
    public fun checked(
        outcome: AnchorOutcome,
        at: Instant,
    )

    /** ストレージや DB に届かず、検査を終えられなかった。 */
    public fun failed(
        error: AuditError,
        at: Instant,
    )

    /** プロセスで最初の回に、ストレージで最後に保存された版を起点にした。 */
    public fun restored(anchor: Anchor)

    public companion object {
        /** 何もしない。 */
        public val NONE: AnchorCycleListener =
            object : AnchorCycleListener {
                override fun checked(
                    outcome: AnchorOutcome,
                    at: Instant,
                ): Unit = Unit

                override fun failed(
                    error: AuditError,
                    at: Instant,
                ): Unit = Unit

                override fun restored(anchor: Anchor): Unit = Unit
            }
    }
}
