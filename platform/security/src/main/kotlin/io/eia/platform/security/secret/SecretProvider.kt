package io.eia.platform.security.secret

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 秘密情報を取得する Port(ADR-0008)。
 *
 * ローカル参照実装は環境変数と Docker secrets のファイルから読む [EnvSecretProvider] を使う。
 * 本番では Vault / OpenBao / クラウドの Secret Manager の Adapter に差し替える(Framework 12.2)。
 *
 * 呼び出し側は値をキャッシュせず、使うたびに取得する(ローテーションに追従するため)。
 */
public fun interface SecretProvider {
    public fun get(name: SecretName): Result<Secret, SecretError>
}

/**
 * 秘密情報を取得できなかった理由。メッセージには名前だけを入れ、値やファイルの中身は入れない。
 *
 * 各実装は [DomainError.Retryable] か [DomainError.NonRetryable] のどちらか一方を実装する
 * ([DomainError] は kernel の sealed interface のため、ここから直接は継承できない。ADR-0011)。
 */
public sealed interface SecretError {
    public val name: SecretName
    public val code: String
    public val message: String
}

/** 設定されていない。設定の漏れなので、リトライしても解決しない。 */
public data class SecretNotFound(
    override val name: SecretName,
) : SecretError,
    DomainError.NonRetryable {
    override val code: String get() = "secret_not_found"
    override val message: String get() = "秘密情報 $name が設定されていません($name か ${name}${SecretName.FILE_SUFFIX})"
}

/** 設定が不正(値とファイルの両方がある・空・大きすぎるなど)。 */
public data class SecretMisconfigured(
    override val name: SecretName,
    public val reason: String,
) : SecretError,
    DomainError.NonRetryable {
    override val code: String get() = "secret_misconfigured"
    override val message: String get() = "秘密情報 $name の設定が不正です: $reason"
}

/** 読み取りに失敗した(ファイルの I/O エラーなど)。ファイルの差し替え中などの一時的な失敗でありうる。 */
public data class SecretUnreadable(
    override val name: SecretName,
    public val reason: String,
) : SecretError,
    DomainError.Retryable {
    override val code: String get() = "secret_unreadable"
    override val message: String get() = "秘密情報 $name を読み取れません: $reason"
}
