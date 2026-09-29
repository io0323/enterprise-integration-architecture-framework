package io.eia.platform.audit.verify

/**
 * 検証で見つかった改竄の疑い。出力には `seq` とアンカーのキーだけを入れ、記録の中身は入れない。
 */
public sealed interface Finding {
    public val code: String

    public fun describe(): String

    // ---- チェーン ----

    /** 記録を直列化し直したハッシュが `hash` 列と一致しない(列の値が書き換えられた)。 */
    public data class HashMismatch(
        val seq: Long,
    ) : Finding {
        override val code: String get() = "hash_mismatch"

        override fun describe(): String = "seq=$seq: 記録の内容とハッシュが一致しません"
    }

    /** `prev_hash` が直前の記録の `hash` と一致しない(記録の削除・差し込み・入れ替え)。先頭では 64 個の 0 と比べる。 */
    public data class BrokenLink(
        val seq: Long,
    ) : Finding {
        override val code: String get() = "broken_link"

        override fun describe(): String = "seq=$seq: prev_hash が直前の記録のハッシュと一致しません"
    }

    /** `seq` が飛んでいる(記録の削除)。 */
    public data class MissingSeq(
        val from: Long,
        val to: Long,
    ) : Finding {
        override val code: String get() = "missing_seq"

        override fun describe(): String = if (from == to) "seq=$from: 記録がありません" else "seq=$from..$to: 記録がありません"
    }

    /** `seq` が昇順でない・重複している(順序の入れ替え)。 */
    public data class OutOfOrder(
        val seq: Long,
        val previous: Long,
    ) : Finding {
        override val code: String get() = "out_of_order"

        override fun describe(): String = "seq=$seq: 直前の seq=$previous より大きくありません"
    }

    /** 記録の `canonical_version` に対応する直列化の方法がない。 */
    public data class UnknownCanonicalVersion(
        val seq: Long,
        val version: Int,
    ) : Finding {
        override val code: String get() = "unknown_canonical_version"

        override fun describe(): String = "seq=$seq: canonical_version=$version の直列化の方法がありません"
    }

    /** 列の値を記録として解釈できない。 */
    public data class MalformedRecord(
        val seq: Long,
        val reason: String,
    ) : Finding {
        override val code: String get() = "malformed_record"

        override fun describe(): String = "seq=$seq: $reason"
    }

    // ---- アンカー ----

    /** アンカーの `seq` の記録のハッシュが、アンカーのハッシュと一致しない。 */
    public data class AnchorHashMismatch(
        val key: String,
        val versionId: String,
        val seq: Long,
    ) : Finding {
        override val code: String get() = "anchor_hash_mismatch"

        override fun describe(): String = "$key (version $versionId): seq=$seq のハッシュがアンカーと一致しません"
    }

    /** アンカーの `seq` の記録がない(アンカーより後の記録が末尾から削除された、または途中が削除された)。 */
    public data class AnchorRecordMissing(
        val key: String,
        val versionId: String,
        val seq: Long,
        val head: Long?,
    ) : Finding {
        override val code: String get() = "anchor_record_missing"

        override fun describe(): String = "$key (version $versionId): アンカーの seq=$seq の記録がありません(現在の末尾 seq=${head ?: "なし"})"
    }

    /** 削除マーカーがある(アンカーを消そうとした跡)。 */
    public data class AnchorDeleteMarker(
        val key: String,
        val versionId: String,
    ) : Finding {
        override val code: String get() = "anchor_delete_marker"

        override fun describe(): String = "$key (version $versionId): 削除マーカーがあります"
    }

    /** 保持モードが COMPLIANCE でない。 */
    public data class AnchorNotCompliance(
        val key: String,
        val versionId: String,
        val mode: String?,
    ) : Finding {
        override val code: String get() = "anchor_not_compliance"

        override fun describe(): String = "$key (version $versionId): 保持モードが COMPLIANCE ではありません(${mode ?: "なし"})"
    }

    /** 保持期限が、保存した時刻 + 設定した最小の保持期間より短い。 */
    public data class AnchorRetentionTooShort(
        val key: String,
        val versionId: String,
    ) : Finding {
        override val code: String get() = "anchor_retention_too_short"

        override fun describe(): String = "$key (version $versionId): 保持期限が設定した最小値より短いか、ありません"
    }

    /** アンカーを読めない・形式が不正・キーとサービスや日付が一致しない。 */
    public data class AnchorInvalid(
        val key: String,
        val versionId: String,
        val reason: String,
    ) : Finding {
        override val code: String get() = "anchor_invalid"

        override fun describe(): String = "$key (version $versionId): $reason"
    }
}
