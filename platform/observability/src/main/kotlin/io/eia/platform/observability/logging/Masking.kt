package io.eia.platform.observability.logging

/**
 * ログに出す文字列から、トークン・秘密情報・個人情報を伏せる(Framework 14.1・14.2 のアンチパターン「ペイロード全文ログ」、CODING_STANDARDS「ロギング」)。
 *
 * [EiaLogEncoder] と [OtlpLogAppender] がメッセージ・例外・MDC の値に必ず適用する。ログを書く側も、外部から受け取った値を
 * メッセージに含めるときは、これに頼らず参照キー(ID など)だけを書く。マスキングは最後の防御であり、網羅は保証しない。
 * 迷う場合は伏せる側に倒す(秘密情報のキーは広めに照合する)。誤検知と見逃しの方針は ADR-0018 §3。
 *
 * 伏せる対象(適用順):
 * 1. PEM の塊(`-----BEGIN ...-----` から `-----END ...-----` まで。閉じなければ入力の末尾まで)。
 *    引用符の有無や改行の種類(`\n` / `\r\n` / JSON の `\n` エスケープ / 改行なし)によらず丸ごと `***` にする([PemBlocks])
 * 2. URL の userinfo(`scheme://user:password@host`)
 * 3. 秘密情報のキーの値。キーは大文字小文字と区切り(`_` / `-` / camelCase)を問わず、前後に語が付いてもよい
 *    (`password`、`newPassword`、`clientSecret`、`x-api-key`、`access_token` など)。
 *    値は引用符なし・`"..."`・`'...'` のどれでもよく、JSON・クエリ文字列・`key=value`・`Authorization: Bearer ...` を扱う。
 * 4. JWT(`eyJ` で始まる 3 区画)と、キーのない `Bearer` / `Basic` の資格情報(資格情報らしい値だけ。[looksLikeCredential])
 * 5. メールアドレス
 * 6. カード番号(13〜19 桁。Luhn の検査に通るものだけ)
 * 7. 電話番号(E.164 の `+` 始まり、携帯の 11 桁(区切りは `-`・空白・なし)、`-` で区切った固定電話)
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

    // 正規表現は、どの開始位置でも読む長さに上限があるようにする(長い入力で入力長の 2 乗の時間がかからないように)。
    // 開始位置は後読みで語の先頭に限り、繰り返しには上限を付ける。回帰テストは MaskingSpec の「処理時間」。
    private val USERINFO = Regex("""(?i)(?<![a-z0-9+.-])([a-z][a-z0-9+.-]{0,31}://)[^\s/@:?#]{1,256}:[^\s/@?#]{1,256}@""")

    /** 秘密情報を表す語。キーの一部に含まれていれば秘密情報のキーとみなす。 */
    private val SECRET_WORDS =
        listOf(
            "passw(?:or)?d",
            // pass は語全体のときだけ(passed / bypass / passenger を秘密情報のキーにしない)
            "(?<![A-Za-z])pass(?:phrase)?(?![a-z])",
            "pwd",
            "secret",
            "token",
            "api[_\\s-]?key",
            "access[_\\s-]?key",
            "private[_\\s-]?key",
            "credentials?",
            "authorization",
            "cookie",
        ).joinToString("|")

    /** キーの前後に付く語(英数字と `_` `-`。32 文字まで)。 */
    private const val KEY_AFFIX = "[A-Za-z0-9_-]{0,32}"

    /**
     * 値(先に書いたものから試す):
     * - 引用符で囲んだ文字列。閉じていなければ入力の末尾まで(切り詰めで閉じ引用符が落ちた場合も伏せる)。
     * - 2 段までの配列・オブジェクト(中身ごと伏せる)。括弧が対応しなければ行末まで。
     * - 引用符のない値(`Bearer xxx` を含む)。
     *
     * java.util.regex は、選択肢を含むグループを `*` で繰り返すと 1 文字ごとに再帰し、長い値でスタックがあふれる。
     * そのため引用符の値は「展開したループ」と強欲な量指定子(`*+`)で書き、繰り返すグループには回数の上限を付ける。
     */
    private const val SECRET_VALUE =
        """"[^"\\]*+(?:\\.[^"\\]*+)*+(?:"|$)""" +
            """|'[^'\\]*+(?:\\.[^'\\]*+)*+(?:'|$)""" +
            """|\[[^\[\]]{0,4096}+(?:\[[^\[\]]{0,4096}+][^\[\]]{0,4096}+){0,64}+]""" +
            """|\{[^{}]{0,4096}+(?:\{[^{}]{0,4096}+}[^{}]{0,4096}+){0,64}+}""" +
            """|[\[{][^\r\n]*+""" +
            """|(?:(?:bearer|basic)\s+)?[^\s,;&})\]"'\[{]+"""

    private val SECRET_PAIR =
        Regex("""(?i)(["']?(?<![A-Za-z0-9_-])$KEY_AFFIX(?:$SECRET_WORDS)$KEY_AFFIX["']?\s*[=:]\s*)($SECRET_VALUE)""")

    private val JWT = Regex("""(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]*+\.[A-Za-z0-9_-]++\.[A-Za-z0-9_-]*+""")
    private val AUTH_SCHEME = Regex("""(?i)\b(bearer|basic)(\s+)([A-Za-z0-9._~+/=-]+)""")

    private val EMAIL_ADDRESS =
        Regex("""(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(?:\.[A-Za-z0-9-]{1,63}){0,8}\.[A-Za-z]{2,24}""")
    private val CARD_CANDIDATE = Regex("""(?<![\w-])\d(?:[ -]?\d){12,18}(?![\w-])""")
    private val PHONE_NUMBER =
        Regex("""(?<![\w-])(?:\+\d{1,3}[ -]?\d{1,4}(?:[ -]?\d{2,4}){1,3}|0[789]0[ -]?\d{4}[ -]?\d{4}|0\d{1,4}-\d{1,4}-\d{4})(?![\w-])""")

    private const val MIN_CARD_DIGITS = 13
    private const val MAX_CARD_DIGITS = 19
    private const val MIN_CREDENTIAL_LENGTH = 8
    private val CREDENTIAL_SYMBOL = Regex("[0-9._~+/=-]")

    /**
     * 伏せた後の文字列がこれより長ければ、先頭と末尾の半分ずつを残して切り詰める(ログの 1 件の大きさの上限)。
     * 末尾を残すのは、例外のスタックトレースの根本原因(`Caused by:`)が末尾に書かれるため。
     * 伏せてから切り詰めるので、切れ目でキーと値が分かれても値が伏せられずに残ることはない。
     */
    public const val MAX_LENGTH: Int = 16 * 1024

    /**
     * 伏せる前の入力の上限(マスキングの処理時間の上限)。超えた分は先頭と末尾を残して捨てる。
     * 末尾は切れ目の後の最初の改行の次から始める(値の途中から始めて、キーのない値が残らないように)。改行がなければ末尾は捨てる。
     */
    public const val MAX_INPUT_LENGTH: Int = 64 * 1024

    /**
     * [text] の中のトークン・秘密情報・個人情報を伏せた文字列を返す。[MAX_LENGTH] を超えた分は切り詰める。
     * 例外は投げない(ログの記録が業務の処理を失敗させないように)。伏せられなかった場合は、元の文字列ではなく [FAILED] を返す。
     */
    public fun mask(text: String): String {
        if (text.isEmpty()) return text
        return try {
            truncate(maskBounded(limitInput(text)))
        } catch (
            @Suppress("SwallowedException") e: StackOverflowError, // 元の文字列を出さないことを優先する
        ) {
            "$FAILED(${text.length} chars)"
        }
    }

    /** 伏せられなかったときに返す値。 */
    public const val FAILED: String = "[masking failed]"

    private fun limitInput(text: String): String {
        if (text.length <= MAX_INPUT_LENGTH) return text
        val half = MAX_INPUT_LENGTH / 2
        val headEnd = headEnd(text, half)
        val tailStart = text.indexOf('\n', text.length - half).let { if (it < 0) text.length else it + 1 }
        return "${text.take(headEnd)}…[truncated ${tailStart - headEnd} chars]…\n${text.substring(tailStart)}"
    }

    /**
     * 先頭側の終わり(#33)。[limit] の位置で語の途中を切ると、切れ目にかかった値(メールアドレス・電話番号・カード番号・JWT)が
     * 崩れた形で残り、どの規則にも当たらずに出力されるため、[limit] の直前の区切り文字の後ろで切る(値を丸ごと捨てる)。
     * 数字の間の空白は、カード番号・電話番号の桁の区切りなので区切り文字にしない。区切り文字がなければ、先頭側は空にする。
     * 後ろへ戻る幅は最大で [limit](入力の長さに比例)。
     */
    private fun headEnd(
        text: String,
        limit: Int,
    ): Int {
        var i = limit - 1
        while (i >= 0) {
            val c = text[i]
            val betweenDigits = c == ' ' && i > 0 && text[i - 1].isDigit() && text[i + 1].isDigit()
            if (c in HEAD_DELIMITERS && !betweenDigits) return i + 1
            i--
        }
        return 0
    }

    /** 先頭側を切る位置にしてよい区切り文字。値の一部になる文字(`@` `.` `-` `+` `_` `=` など)は含めない。 */
    private val HEAD_DELIMITERS = setOf(' ', '\t', '\n', '\r', ',', ';', '"', '\'', '<', '>', '(', ')', '[', ']', '{', '}', '|', '`')

    /** 伏せた後の文字列を切り詰める。値はすでに伏せてあるので、どこで切っても秘密情報は出ない。 */
    private fun truncate(masked: String): String {
        if (masked.length <= MAX_LENGTH) return masked
        val half = MAX_LENGTH / 2
        return "${masked.take(half)}…[truncated ${masked.length - MAX_LENGTH} chars]…${masked.takeLast(half)}"
    }

    private fun maskBounded(text: String): String {
        // PEM は秘密情報のキーの規則より先に伏せる(先にキーの規則が `-----BEGIN` だけを伏せると、塊の先頭が分からなくなる)
        var masked = PemBlocks.mask(text, SECRET)
        masked = USERINFO.replace(masked) { "${it.groupValues[1]}$SECRET:$SECRET@" }
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

            '[', '{' -> "\"$SECRET\""

            // 配列・オブジェクトは中身ごと 1 つの文字列にして、JSON の形を保つ
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
