package io.eia.platform.security.secret

import java.security.MessageDigest

/**
 * 秘密情報の値(パスワード・Client Secret・鍵など。Framework 12.2)。
 *
 * - [toString] は値を伏せる。ログ・例外のメッセージ・文字列補間に入れても値は出ない。
 * - 値は [reveal] でだけ取り出す。取り出した値を変数に残したり、ログに渡したりしない。
 * - data class にしない(自動生成の `toString` / `componentN` / `copy` から値が漏れるため)。
 * - [equals] は定数時間で比べる(比較にかかる時間から値を推測させない)。
 */
public class Secret(
    private val value: String,
) {
    init {
        require(value.isNotEmpty()) { "Secret の値が空です" }
    }

    /** 値を取り出す。HTTP のヘッダやドライバの設定に渡す直前にだけ呼ぶ。 */
    public fun reveal(): String = value

    override fun toString(): String = MASKED

    override fun equals(other: Any?): Boolean =
        other is Secret && MessageDigest.isEqual(value.toByteArray(Charsets.UTF_8), other.value.toByteArray(Charsets.UTF_8))

    // 値からハッシュを作らない(HashMap のキーにしたときの挙動から値を推測させない。等しい Secret が同じハッシュになる条件は満たす)
    override fun hashCode(): Int = 0

    public companion object {
        public const val MASKED: String = "Secret(***)"
    }
}

/**
 * 秘密情報の名前(環境変数の名前と同じ規則: `[A-Z][A-Z0-9_]*`)。
 *
 * 名前は秘密ではないため、ログやエラーのメッセージに出してよい。`_FILE` で終わる名前は、Docker secrets の
 * ファイルのパスを渡す変数([EnvSecretProvider])と紛らわしいため使えない。
 */
@JvmInline
public value class SecretName(
    public val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "SecretName は [A-Z][A-Z0-9_]* の形にしてください: $value" }
        require(!value.endsWith(FILE_SUFFIX)) { "SecretName は $FILE_SUFFIX で終われません: $value" }
    }

    override fun toString(): String = value

    public companion object {
        public const val FILE_SUFFIX: String = "_FILE"
        private val PATTERN = Regex("[A-Z][A-Z0-9_]{0,127}")
    }
}
