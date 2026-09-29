package io.eia.platform.audit.verify

import io.eia.platform.audit.anchor.Anchor
import io.eia.platform.audit.anchor.AnchorKeys
import io.eia.platform.audit.anchor.AnchorVersion
import io.eia.platform.audit.anchor.ServiceName
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.getOrNull
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * アンカーの全版を、チェーンの検証結果と照合する(ADR-0017)。
 *
 * 版ごとに確かめること:
 * - 削除マーカーでない
 * - 保持モードが COMPLIANCE で、保持期限が「ストレージが記録した保存の時刻 + [minRetention]」以上([clockSkew] だけ許容する)
 * - 内容がアンカーの形式で、サービス名とキーの日付(UTC)が一致する
 * - アンカーの `seq` の記録があり、その `hash` がアンカーと一致する(アンカーより後の記録を末尾から削除しても、ここで検出する)
 */
public class AnchorVerifier(
    private val service: ServiceName,
    private val minRetention: Duration,
    private val clockSkew: Duration = DEFAULT_CLOCK_SKEW,
) {
    /** チェーンの検証の前に、照合に使う `seq`(チェックポイント)を得る。 */
    public fun checkpoints(versions: List<AnchorVersion>): Set<Long> =
        versions.mapNotNull { version -> version.body?.let { Anchor.parse(it).getOrNull()?.seq } }.toSet()

    public fun verify(
        versions: List<AnchorVersion>,
        chain: ChainResult,
    ): List<Finding> = versions.flatMap { verifyVersion(it, chain) }

    @Suppress("ReturnCount") // 削除マーカー・内容・形式のそれぞれで、以降を確かめられないときに返す
    private fun verifyVersion(
        version: AnchorVersion,
        chain: ChainResult,
    ): List<Finding> {
        val key = version.key
        val id = version.versionId
        if (version.isDeleteMarker) return listOf(Finding.AnchorDeleteMarker(key, id))
        val findings = mutableListOf<Finding>()
        if (version.retentionMode != COMPLIANCE) findings += Finding.AnchorNotCompliance(key, id, version.retentionMode)
        val required = version.lastModified.plus(minRetention).minus(clockSkew)
        if (version.retainUntil == null || version.retainUntil < required) findings += Finding.AnchorRetentionTooShort(key, id)
        val body = version.body ?: return findings + Finding.AnchorInvalid(key, id, version.readError ?: "内容を読めません")
        val anchor =
            when (val parsed = Anchor.parse(body)) {
                is Result.Ok -> parsed.value
                is Result.Err -> return findings + Finding.AnchorInvalid(key, id, parsed.error)
            }
        consistencyProblem(anchor, key)?.let { return findings + Finding.AnchorInvalid(key, id, it) }
        val observed = chain.observedHashes[anchor.seq]
        findings +=
            when {
                observed == null -> listOf(Finding.AnchorRecordMissing(key, id, anchor.seq, chain.headSeq))
                observed != anchor.hash -> listOf(Finding.AnchorHashMismatch(key, id, anchor.seq))
                else -> emptyList()
            }
        return findings
    }

    @Suppress("ReturnCount") // 項目ごとに、最初の不一致で返す
    private fun consistencyProblem(
        anchor: Anchor,
        key: String,
    ): String? {
        if (anchor.service != service.value) return "アンカーのサービス名がキーと一致しません"
        val createdAt =
            try {
                Instant.parse(anchor.createdAt)
            } catch (e: DateTimeParseException) {
                return "created_at を解釈できません(${e::class.simpleName})"
            }
        return if (AnchorKeys.of(service, createdAt) == key) null else "キーの日付が created_at の UTC の日付と一致しません"
    }

    public companion object {
        public const val COMPLIANCE: String = "COMPLIANCE"

        /** アプリ(保持期限を計算する)とストレージ(保存の時刻を記録する)の時計のずれの許容。 */
        public val DEFAULT_CLOCK_SKEW: Duration = Duration.ofMinutes(5)
    }
}
