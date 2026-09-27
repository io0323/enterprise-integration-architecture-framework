package io.eia.platform.observability.logging

/**
 * ログに出す文字列から、トークン・秘密情報・個人情報を伏せる(Framework 14.1・14.2 のアンチパターン「ペイロード全文ログ」、CODING_STANDARDS「ロギング」)。
 *
 * [EiaLogEncoder] と [OtlpLogAppender] がメッセージ・例外・MDC の値に必ず適用する。ログを書く側も、外部から受け取った値を
 * メッセージに含めるときは、これに頼らず参照キー(ID など)だけを書く。マスキングは最後の防御であり、網羅は保証しない。
 * 迷う場合は伏せる側に倒す(秘密情報のキーは広めに照合する)。誤検知と見逃しの方針は ADR-0018 §3。
 *
 * 伏せる対象(適用順):
 * 1. URL の userinfo(`scheme://user:password@host`)
 * 2. 秘密情報のキーの値。キーは大文字小文字と区切り(`_` / `-` / camelCase)を問わず、前後に語が付いてもよい
 *    (`password`、`newPassword`、`clientSecret`、`x-api-key`、`access_token` など)。
 *    値は引用符なし・`"..."`・`'...'` のどれでもよく、JSON・クエリ文字列・`key=value`・`Authorization: Bearer ...` を扱う。
 * 3. JWT(`eyJ` で始まる 3 区画)と、キーのない `Bearer` / `Basic` の資格情報(資格情報らしい値だけ。[looksLikeCredential])
 * 4. メールアドレス
 * 5. カード番号(13〜19 桁。Luhn の検査に通るものだけ)
 * 6. 電話番号(E.164 の `+` 始まり、`-` か空白で区切った国内の番号、区切りのない携帯の 11 桁)
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

    private val USERINFO = Regex("""(?i)\b([a-z][a-z0-9+.-]*://)[^\s/@:]+:[^\s/@]+@""")

    /** 秘密情報を表す語。キーの一部に含まれていれば秘密情報のキーとみなす。 */
    private val SECRET_WORDS =
        listOf(
            "passw(?:or)?d",
            "pwd",
            "secret",
            "token",
            "api[_-]?key",
            "access[_-]?key",
            "private[_-]?key",
            "credentials?",
            "authorization",
            "cookie",
        ).joinToString("|")

    /** キー(前後に英数字と `_` `-` を許す)。引用符で囲まれていてもよい。 */
    private const val KEY_AFFIX = "[A-Za-z0-9_-]*"

    private val SECRET_PAIR =
        Regex(
            """(?i)(["']?\b$KEY_AFFIX(?:$SECRET_WORDS)$KEY_AFFIX["']?\s*[=:]\s*)""" +
                """("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|(?:(?:bearer|basic)\s+)?[^\s,;&})\]"']+)""",
        )

    private val JWT = Regex("""\beyJ[A-Za-z0-9_-]*\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*""")
    private val AUTH_SCHEME = Regex("""(?i)\b(bearer|basic)(\s+)([A-Za-z0-9._~+/=-]+)""")

    private val EMAIL_ADDRESS = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")
    private val CARD_CANDIDATE = Regex("""(?<![\w-])\d(?:[ -]?\d){12,18}(?![\w-])""")
    private val PHONE_NUMBER =
        Regex("""(?<![\w-])(?:\+\d{1,3}[ -]?\d{1,4}(?:[ -]?\d{2,4}){1,3}|0\d{1,4}[ -]\d{1,4}[ -]\d{3,4}|0[789]0\d{8})(?![\w-])""")

    private const val MIN_CARD_DIGITS = 13
    private const val MAX_CARD_DIGITS = 19
    private const val MIN_CREDENTIAL_LENGTH = 8
    private val CREDENTIAL_SYMBOL = Regex("[0-9._~+/=-]")

    /** [text] の中のトークン・秘密情報・個人情報を伏せた文字列を返す。 */
    public fun mask(text: String): String {
        if (text.isEmpty()) return text
        var masked = USERINFO.replace(text) { "${it.groupValues[1]}$SECRET:$SECRET@" }
        masked = SECRET_PAIR.replace(masked) { "${it.groupValues[1]}${quoted(it.groupValues[2])}" }
        masked = JWT.replace(masked, TOKEN)
        masked =
            AUTH_SCHEME.replace(masked) { match ->
                val (scheme, space, credential) = match.destructured
                if (looksLikeCredential(credential)) "$scheme$space$TOKEN" else match.value
            }
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

    /** 元の値の引用符を保って伏せる(JSON の形を壊さない)。 */
    private fun quoted(value: String): String =
        when (value.firstOrNull()) {
            '"' -> "\"$SECRET\""
            '\'' -> "'$SECRET'"
            else -> SECRET
        }

    /**
     * キーのない `Bearer xxx` / `Basic xxx` の `xxx` が資格情報らしいか。8 文字以上で、数字・記号を含むか大文字と小文字が混ざる。
     * 「basic authentication」「Bearer tokens」のような文章を伏せないため。
     */
    private fun looksLikeCredential(value: String): Boolean =
        value.length >= MIN_CREDENTIAL_LENGTH &&
            (CREDENTIAL_SYMBOL.containsMatchIn(value) || (value.any(Char::isUpperCase) && value.any(Char::isLowerCase)))

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
