package io.eia.fileexchange.domain

/** 受け取ったファイルの移動先(ADR-0032 §4)。 */
public enum class ReceiptDestination {
    /** 合格。archive/ に移す */
    ARCHIVE,

    /** 不合格(改竄・欠損・形式の誤り)。quarantine/ に移し、理由のファイルを置く */
    QUARANTINE,
}

/** 受け取りの記録の状態(ADR-0032 §5)。 */
public enum class ReceiptState {
    /** 移動の決定を記録し、移している途中。やり直しでは検査をせずに、記録した移動を続ける */
    MOVING,

    /** 移し終えた */
    STORED,
}

/**
 * 受け取りの記録(file-exchange の DB の `received_file`。ファイル名ごとに 1 行。ADR-0032 §5)。
 *
 * @property name ファイルの名前の拡張子を除いた部分(`{system}_{dataset}_{yyyyMMddHHmmss}_{seq}`)
 * @property sha256 マニフェストの SHA-256(マニフェストが読めなかったときは null)
 * @property dataFile データファイルの名前(合格のときだけ。archive の移動の確認に使う)
 * @property reason 不合格の理由のコード(合格のときは null)
 * @property detail 不合格の固定の説明(ファイルの中身の値は入れない)
 */
public data class ReceivedFile(
    val name: String,
    val sha256: String?,
    val destination: ReceiptDestination,
    val dataFile: String?,
    val reason: String?,
    val detail: String?,
    val state: ReceiptState,
) {
    init {
        require((destination == ReceiptDestination.ARCHIVE) == (reason == null)) { "不合格のときだけ理由を持ちます" }
        require(destination == ReceiptDestination.QUARANTINE || dataFile != null) { "合格のときはデータファイルの名前が要ります" }
    }
}

/** 受け取り済みの名前で、また届いたファイル(送り直し・名前の使い回し)の理由。 */
public enum class ExtraArrivalReason(
    public val code: String,
) {
    /** 同じ名前・同じ SHA-256(送り直し) */
    DUPLICATE("duplicate"),

    /** 同じ名前で、SHA-256 が違う(または読めない) */
    NAME_REUSED("name_reused"),
    ;

    public companion object {
        /**
         * 受け取り済みの [received] と同じ名前で、[arrivedSha256] のファイルが届いたときの理由。
         * どちらかの SHA-256 が分からなければ、同じ中身とは言えないので [NAME_REUSED]。
         */
        public fun of(
            received: ReceivedFile,
            arrivedSha256: String?,
        ): ExtraArrivalReason = if (received.sha256 != null && received.sha256 == arrivedSha256) DUPLICATE else NAME_REUSED
    }
}

/**
 * 受け取り済みの名前で届いたファイルの処理の記録(`extra_arrival`)。同じ名前の処理中のものは 1 つだけ(DB の一意の制約)。
 *
 * @property id 置き場の移動先を分ける番号(同じ名前で何度届いても、quarantine の中で上書きしない)
 */
public data class ExtraArrival(
    val id: Long,
    val name: String,
    val sha256: String?,
    val reason: ExtraArrivalReason,
)
