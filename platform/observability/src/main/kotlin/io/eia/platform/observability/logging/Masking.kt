package io.eia.platform.observability.logging

/**
 * ログに出す文字列から、トークン・秘密情報・個人情報を伏せる(Framework 14.1・14.2 のアンチパターン「ペイロード全文ログ」、CODING_STANDARDS「ロギング」)。
 *
 * [EiaLogEncoder] と [OtlpLogAppender] がメッセージ・例外・MDC の値に必ず適用する。ログを書く側も、外部から受け取った値を
 * メッセージに含めるときは、これに頼らず参照キー(ID など)だけを書く。マスキングは最後の防御であり、網羅は保証しない。
 *
 * 伏せる対象(適用順):
 * 1. 秘密情報のキーの値(`password=...`、`"client_secret": "..."`、`Authorization: Bearer ...` など。JSON とクエリ文字列の両方)
 * 2. JWT(`eyJ` で始まる 3 区画)と、キーのない `Bearer` / `Basic` の資格情報
 * 3. メールアドレス
 * 4. カード番号(13〜19 桁。Luhn の検査に通るものだけ)
 * 5. 電話番号(E.164 の `+` 始まり、区切りのある番号、国内の 0 始まりの 10〜11 桁)
 */
public object Masking {
    public const val TOKEN: String = "[token]"
    public const val SECRET: String = "***"
    public const val EMAIL: String = "[email]"
    public const val CARD: String = "[card]"
    public const val PHONE: String = "[phone]"

    /** 値を記録してはならないヘッダ(小文字)。 */
    public val SENSITIVE_HEADERS: Set<String> =
        setOf("authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "x-amz-security-token")

    private val JWT = Regex("""\beyJ[A-Za-z0-9_-]*\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*""")
    private val AUTH_SCHEME = Regex("""(?i)\b(bearer|basic)(\s+)[A-Za-z0-9._~+/=-]+""")

    private val SECRET_KEYS =
        listOf(
            "password",
            "passwd",
            "pwd",
            "secret",
            "client_secret",
            "token",
            "access_token",
            "refresh_token",
            "id_token",
            "api_key",
            "apikey",
            "authorization",
            "credential",
            "credentials",
            "private_key",
        ).joinToString("|")

    /** JSON の `"key": "value"`。値の中のエスケープした引用符も扱う。 */
    private val SECRET_JSON = Regex("""(?i)("(?:$SECRET_KEYS)"\s*:\s*)"(?:[^"\\]|\\.)*"""")

    /** `key=value` / `key: value`(クエリ文字列・ヘッダ・ログの key=value)。`Authorization: Bearer xxx` は方式ごと伏せる。 */
    private val SECRET_PAIR = Regex("""(?i)\b((?:$SECRET_KEYS)\s*[=:]\s*)(?!["\s])(?:(?:bearer|basic)\s+)?[^\s,;&}\]]+""")

    private val EMAIL_ADDRESS = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")
    private val CARD_CANDIDATE = Regex("""(?<![\w-])\d(?:[ -]?\d){12,18}(?![\w-])""")
    private val PHONE_NUMBER =
        Regex("""(?<![\w-])(?:\+\d{1,3}[ -]?\d{1,4}(?:[ -]?\d{2,4}){1,3}|0\d{1,4}-\d{1,4}-\d{3,4}|0\d{9,10})(?![\w-])""")

    private const val MIN_CARD_DIGITS = 13
    private const val MAX_CARD_DIGITS = 19

    /** [text] の中のトークン・秘密情報・個人情報を伏せた文字列を返す。 */
    public fun mask(text: String): String {
        if (text.isEmpty()) return text
        var masked = SECRET_JSON.replace(text) { "${it.groupValues[1]}\"$SECRET\"" }
        masked = SECRET_PAIR.replace(masked) { "${it.groupValues[1]}$SECRET" }
        masked = JWT.replace(masked, TOKEN)
        masked = AUTH_SCHEME.replace(masked) { "${it.groupValues[1]}${it.groupValues[2]}$TOKEN" }
        masked = EMAIL_ADDRESS.replace(masked, EMAIL)
        masked = CARD_CANDIDATE.replace(masked) { if (isCardNumber(it.value)) CARD else it.value }
        masked = PHONE_NUMBER.replace(masked, PHONE)
        return masked
    }

    /** ヘッダを記録するときに使う。[SENSITIVE_HEADERS] は値を伏せ、それ以外は [mask] を通す。 */
    public fun maskHeader(
        name: String,
        value: String,
    ): String = if (name.lowercase() in SENSITIVE_HEADERS) SECRET else mask(value)

    @Suppress("MagicNumber") // Luhn の検査(桁を 2 倍して 9 を引く)
    private fun isCardNumber(candidate: String): Boolean {
        val digits = candidate.filter(Char::isDigit)
        if (digits.length !in MIN_CARD_DIGITS..MAX_CARD_DIGITS) return false
        val sum =
            digits.reversed().foldIndexed(0) { index, acc, c ->
                val d = c.digitToInt()
                acc + if (index % 2 == 1) (d * 2).let { if (it > 9) it - 9 else it } else d
            }
        return sum % 10 == 0
    }
}
