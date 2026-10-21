package io.eia.platform.security.tls

/**
 * mTLS の設定の環境変数の名前(ADR-0024 §6)。サービスごとに接頭辞だけを変える(例 `ORDER` → `ORDER_TLS_CERT_FILE`)。
 *
 * | 環境変数 | 内容 |
 * |---|---|
 * | `{PREFIX}_TLS_CERT_FILE` / `{PREFIX}_TLS_KEY_FILE` | サーバ証明書のチェーンと秘密鍵(PEM。鍵は PKCS#8) |
 * | `{PREFIX}_TLS_CLIENT_CA_FILE` | クライアント証明書を検証する CA(PEM) |
 * | `{PREFIX}_TLS_ALLOWED_CLIENTS` | 受け入れるクライアント証明書の SAN の DNS 名(カンマ区切り。既定 `apisix`) |
 */
public data class TlsVariables(
    val prefix: String,
) {
    init {
        require(PREFIX.matches(prefix)) { "接頭辞は英大文字・数字・_ にしてください" }
    }

    public val cert: String get() = "${prefix}_TLS_CERT_FILE"
    public val key: String get() = "${prefix}_TLS_KEY_FILE"
    public val clientCa: String get() = "${prefix}_TLS_CLIENT_CA_FILE"
    public val allowedClients: String get() = "${prefix}_TLS_ALLOWED_CLIENTS"

    /** ファイルの誤りをまとめて示す名前(どのファイルか分からない読み取りの失敗に使う)。 */
    public val all: String get() = "${prefix}_TLS_*"

    /** 環境変数からファイルのパスを読む(空は設定なし)。 */
    public fun files(env: Map<String, String>): TlsFiles =
        TlsFiles(
            this,
            env[cert]?.takeIf { it.isNotBlank() },
            env[key]?.takeIf { it.isNotBlank() },
            env[clientCa]?.takeIf { it.isNotBlank() },
        )

    /** 許可の一覧(空の名前は除く)。設定がなければ [DEFAULT_ALLOWED_CLIENTS]。空になったら呼び出し側で設定の誤りにする。 */
    public fun allowedClients(env: Map<String, String>): Set<String> =
        (env[allowedClients]?.takeIf { it.isNotBlank() } ?: DEFAULT_ALLOWED_CLIENTS)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    public companion object {
        /** ゲートウェイ(APISIX)のクライアント証明書の SAN。 */
        public const val DEFAULT_ALLOWED_CLIENTS: String = "apisix"
        private val PREFIX = Regex("^[A-Z][A-Z0-9_]*$")
    }
}

/** TLS のファイルのパス(serve で必須。migrate では使わない)。誤りのメッセージには [variables] の名前を書く。 */
public data class TlsFiles(
    val variables: TlsVariables,
    val cert: String?,
    val key: String?,
    val clientCa: String?,
)
