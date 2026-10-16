package io.eia.fileexchange.domain

/**
 * 取引先の ID(取引先の台帳のキー。P12 の B2B Gateway の取引先の台帳に引き継ぐ。ADR-0032 §8)。
 * 英小文字・数字・ハイフンだけ(メトリクスの属性と、Resilience の名前 `sftp-{id}` に使う)。
 */
public data class PartnerId(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "取引先の ID は英小文字・数字・ハイフンの 1〜32 文字にしてください" }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,31}$")
    }
}

/** 取引先への配送の状態(file-exchange の DB の `delivery`。ADR-0032 §8)。 */
public enum class DeliveryState {
    /** 配送を始めた(送っている途中で落ちたかもしれない)。やり直しでは、送る前に相手の側に正式名があるかを確かめる */
    SENDING,

    /** 送り終えた(または相手の側にすでにあった) */
    SENT,
}

/**
 * 受け取ったファイルの、取引先ごとの配送の記録(ファイル名と取引先の組ごとに 1 行)。
 *
 * @property name 受け取ったファイルの名前の拡張子を除いた部分
 * @property remoteName 相手の側の正式名(データファイルの名前 + `.pgp`)
 * @property alreadyPresent 送る前に確かめたら、相手の側にすでに正式名があった(前の実行が正式名に変えた後、記録する前に落ちた)
 */
public data class Delivery(
    val name: String,
    val partner: PartnerId,
    val remoteName: String,
    val state: DeliveryState,
    val alreadyPresent: Boolean,
) {
    public companion object {
        /** PGP で暗号化・署名したファイルの拡張子 */
        public const val ENCRYPTED_SUFFIX: String = ".pgp"

        /** 相手の側の正式名。データファイルの名前に [ENCRYPTED_SUFFIX] を付ける。 */
        public fun remoteName(dataFile: String): String = dataFile + ENCRYPTED_SUFFIX
    }
}

/** 配送の結果(メトリクスの属性 `file_exchange.outcome`)。 */
public enum class DeliveryOutcome(
    public val code: String,
) {
    /** 送った */
    SENT("sent"),

    /** 相手の側にすでに正式名があったので、送らずに送信済みにした */
    ALREADY_PRESENT("already_present"),

    /** 前の実行で送信済み。何もしなかった */
    ALREADY_SENT("already_sent"),
}

/**
 * データセットのキー `{system}_{dataset}`(取引先の台帳で、取引先に送るファイルを選ぶのに使う)。
 * ファイルの名前 `{system}_{dataset}_{yyyyMMddHHmmss}_{seq}` から、時刻と連番を除いたもの。形が違えば null。
 */
public object DatasetKey {
    private val NAME = Regex("^([a-z0-9]+_[a-z0-9_]+)_[0-9]{14}_[0-9]{3,6}$")

    public fun of(name: String): String? = NAME.matchEntire(name)?.groupValues?.get(1)
}
