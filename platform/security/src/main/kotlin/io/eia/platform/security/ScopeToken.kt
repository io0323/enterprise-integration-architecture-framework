package io.eia.platform.security

/** スコープの値の形式(RFC 6749 §3.3 の scope-token)。受信側の [io.eia.platform.security.ktor.requireScopes] と取得側の設定で共有する。 */
internal object ScopeToken {
    /** `%x21 / %x23-5B / %x5D-7E`。`"` と `\` と空白を含まない(`WWW-Authenticate` の quoted-string に安全に入れられる)。 */
    private val PATTERN = Regex("[\\x21\\x23-\\x5B\\x5D-\\x7E]+")

    fun require(scope: String) {
        require(PATTERN.matches(scope)) { "スコープの形式が不正です(RFC 6749 §3.3 の scope-token): $scope" }
    }
}
