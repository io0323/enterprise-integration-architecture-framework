package io.eia.shared.sdk.http.token

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * Client Secret の値。[toString] は値を伏せる(ログ・例外に入れても値は出ない)。
 * data class にしない(自動生成の `toString` / `componentN` / `copy` から値が漏れるため)。
 */
public class ClientSecret(
    private val value: String,
) {
    init {
        require(value.isNotEmpty()) { "Client Secret が空です" }
    }

    /** 値。`Authorization` のヘッダを作る直前にだけ使う。 */
    public fun reveal(): String = value

    override fun toString(): String = "***"
}

/**
 * Client Secret の取得元。[ClientCredentialsTokenProvider] は取得のたびに呼ぶ(ローテーションに追従する)。
 * JVM の platform/security では SecretProvider から読む。失敗は Retryable / NonRetryable の [DomainError] で返す
 * ([ClientSecretUnavailable] に包まれる)。
 */
public fun interface ClientSecretSource {
    public fun get(): Result<ClientSecret, DomainError>
}

/**
 * [ClientCredentialsTokenProvider] の出来事の通知(ログの出力は呼び出し側が決める。KMP のコードはログのライブラリに依存しない)。
 * 引数には、トークン・Client Secret・応答の本文を入れない。
 */
public interface TokenProviderListener {
    /** 期限前の取り直しに失敗し、期限内のトークンを使い続ける(Fallback)。 */
    public fun onStaleTokenUsed(
        error: TokenError,
        expiresAt: kotlin.time.Instant?,
    ) {}

    /** トークンを取得できず、失敗を返す。 */
    public fun onFetchFailed(error: TokenError) {}

    /** トークンエンドポイントに接続できない。[exceptionType] は例外の型の名前だけ(メッセージは URL やヘッダを含みうるため渡さない)。 */
    public fun onUnreachable(exceptionType: String?) {}

    public companion object {
        public val NONE: TokenProviderListener = object : TokenProviderListener {}
    }
}
