package io.eia.platform.filetransfer.verify

import io.eia.platform.filetransfer.manifest.FileManifest

/**
 * 受け取ったファイルの検査の結果(ADR-0032 §3)。検査は判定だけを返し、ファイルを動かさない。
 * 移動([io.eia.platform.filetransfer.move.FileMover])は、呼び出し側が結果を記録してから行う(ADR-0032 §4・§5)。
 *
 * @property base ファイルの名前の拡張子を除いた部分(マニフェストのキーから求める)。移動に渡す
 */
public sealed interface Verification {
    public val base: String

    /** 合格。[manifest] の SHA-256・件数・スキーマの版が、データファイルと一致した。 */
    public data class Accepted(
        override val base: String,
        val manifest: FileManifest,
        val dataKey: String,
    ) : Verification

    /**
     * 不合格(改竄・欠損・形式の誤り)。quarantine に移す。
     *
     * @property manifest 形式が正しく読めたときだけ
     * @property detail 固定の説明だけ(ファイルの中身の値は入れない)
     */
    public data class Rejected(
        override val base: String,
        val reason: RejectionReason,
        val detail: String,
        val manifest: FileManifest?,
    ) : Verification
}

/** 不合格の理由(メトリクスのラベルと、quarantine の理由のファイルに使う)。 */
public enum class RejectionReason(
    public val code: String,
) {
    /** マニフェストが JSON として読めない・必須の項目がない・形式が誤っている */
    MANIFEST_INVALID("manifest_invalid"),

    /** マニフェストの file が、マニフェストの名前と対応しない */
    NAME_MISMATCH("name_mismatch"),

    /** 知らないスキーマの版(契約にない) */
    SCHEMA_VERSION_UNKNOWN("schema_version_unknown"),

    /** 拡張子が契約の形式にない */
    FORMAT_UNSUPPORTED("format_unsupported"),

    /** データファイルがない(欠損) */
    DATA_MISSING("data_missing"),

    /** データファイルの大きさがマニフェストと違う */
    SIZE_MISMATCH("size_mismatch"),

    /** SHA-256 がマニフェストと違う(改竄・転送の誤り) */
    CHECKSUM_MISMATCH("checksum_mismatch"),

    /** データファイルを形式どおりに読めない(壊れた Parquet・gzip・閉じていない囲み) */
    CONTENT_UNREADABLE("content_unreadable"),

    /** 列(名前・順序・型・nullable)が契約と違う */
    SCHEMA_MISMATCH("schema_mismatch"),

    /** 件数がマニフェストと違う */
    RECORD_COUNT_MISMATCH("record_count_mismatch"),

    /**
     * 受け取り済みのファイルと同じ名前・同じ SHA-256 のファイルがまた届いた(送り直し)。検査では返さない。
     * 受け手が受け取りの記録と照らして決める(ADR-0032 §5)
     */
    DUPLICATE("duplicate"),

    /** 受け取り済みのファイルと同じ名前で、SHA-256 が違うファイルが届いた(名前の使い回し)。検査では返さない(ADR-0032 §5) */
    NAME_REUSED("name_reused"),
}
