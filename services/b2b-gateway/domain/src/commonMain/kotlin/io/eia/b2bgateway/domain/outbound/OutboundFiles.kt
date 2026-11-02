package io.eia.b2bgateway.domain.outbound

/**
 * 取引先に送るファイル(INT-SALES-004 の日次の売上ファイルなど。ADR-0040 §7)の名前の規則。
 * ファイルの名前は `{system}_{dataset}_{yyyyMMddHHmmss}_{seq}`(contracts/files の FileName。拡張子を除く)。
 */
public object OutboundFiles {
    private val NAME = Regex("^([a-z0-9]+_[a-z0-9_]+)_[0-9]{14}_[0-9]{3,6}$")

    /** PGP で暗号化・署名したファイルの拡張子(取引先の側の正式名。ADR-0032 §8 と同じ) */
    public const val ENCRYPTED_SUFFIX: String = ".pgp"

    /** 取引先の台帳で送り先を選ぶデータセットのキー `{system}_{dataset}`。名前の形が違えば null。 */
    public fun dataset(name: String): String? = NAME.matchEntire(name)?.groupValues?.get(1)

    /** 取引先の側の正式名。データファイルの名前に [ENCRYPTED_SUFFIX] を付ける(送り直しても同じ名前)。 */
    public fun remoteName(dataFile: String): String = dataFile + ENCRYPTED_SUFFIX
}
