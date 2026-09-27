package io.eia.platform.security.ktor

import io.eia.platform.security.jwt.JwtVerificationError
import io.eia.platform.security.jwt.JwtVerifier
import io.eia.platform.security.jwt.VerifiedToken
import io.eia.shared.kernel.Result
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.principal
import io.ktor.util.AttributeKey

/**
 * `Authorization: Bearer <JWT>` を [JwtVerifier] で検証する Ktor の認証 provider(Framework 12.1。ADR-0019)。
 *
 * ```
 * install(Authentication) {
 *     eiaJwt { verifier = JwtVerifier(JwtVerifierConfig(issuer, "order-api", jwksUri), meter = runtime.meter) }
 * }
 * routing {
 *     authenticate {
 *         requireScopes("sales.order:write") { post("/v1/orders") { ... } }
 *     }
 * }
 * ```
 *
 * - トークンがない(または Bearer 以外の方式)なら 401 と `WWW-Authenticate: Bearer`(RFC 6750 §3.1 のとおり `error` を付けない)。
 * - トークンが不正なら 401 と `WWW-Authenticate: Bearer error="invalid_token"`。`Authorization` が複数ある場合も不正とする。
 * - JWKS を取得できず検証できないなら 503(トークンの問題ではないため)。
 * - 検証を通ったら [VerifiedToken] を principal にする。スコープの認可は [requireScopes] で行う。
 */
public class EiaJwtAuthenticationProvider internal constructor(
    config: Config,
) : AuthenticationProvider(config) {
    private val verifier: JwtVerifier = requireNotNull(config.verifier) { "eiaJwt には verifier(JwtVerifier)が必要です" }
    private val realm: String? = config.realm

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val headers =
            context.call.request.headers
                .getAll(HttpHeaders.Authorization)
                .orEmpty()
        val credential =
            when {
                headers.isEmpty() -> Credential.Missing
                headers.size > 1 -> Credential.Invalid
                else -> credential(headers.single())
            }
        when (credential) {
            Credential.Missing -> {
                context.challenge(CHALLENGE_KEY, AuthenticationFailedCause.NoCredentials) { challenge, call ->
                    BearerChallenge.missingToken(call, realm)
                    challenge.complete()
                }
            }

            Credential.Invalid -> {
                challengeInvalid(context)
            }

            is Credential.Bearer -> {
                when (val result = verifier.verify(credential.token)) {
                    is Result.Ok -> {
                        realm?.let { context.call.attributes.put(REALM, it) }
                        context.principal(name, result.value)
                    }

                    is Result.Err -> {
                        when (result.error) {
                            is JwtVerificationError.InvalidToken -> {
                                challengeInvalid(context)
                            }

                            JwtVerificationError.KeysUnavailable -> {
                                context.challenge(CHALLENGE_KEY, AuthenticationFailedCause.Error("jwks_unavailable")) { challenge, call ->
                                    BearerChallenge.keysUnavailable(call)
                                    challenge.complete()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun challengeInvalid(context: AuthenticationContext) {
        context.challenge(CHALLENGE_KEY, AuthenticationFailedCause.InvalidCredentials) { challenge, call ->
            BearerChallenge.invalidToken(call, realm)
            challenge.complete()
        }
    }

    public class Config internal constructor(
        name: String?,
    ) : AuthenticationProvider.Config(name) {
        /** トークンの検証器。アプリの終了時に close する。 */
        public var verifier: JwtVerifier? = null

        /** `WWW-Authenticate` の realm。なければ付けない。 */
        public var realm: String? = null
            set(value) {
                value?.let(BearerChallenge::requireValidRealm)
                field = value
            }
    }

    /** `Authorization` の解釈の結果。トークンの値は [Bearer] だけが持ち、toString でも出さない。 */
    internal sealed interface Credential {
        /** ない、または Bearer 以外の方式。 */
        data object Missing : Credential

        /** Bearer だが形式が不正、または `Authorization` が複数ある。 */
        data object Invalid : Credential

        class Bearer(
            val token: String,
        ) : Credential {
            override fun toString(): String = "Bearer(***)"
        }
    }

    internal companion object {
        private const val CHALLENGE_KEY = "EiaJwt"

        /** 認証を通した provider の realm。[requireScopes] の 403 の `WWW-Authenticate` に同じ realm を入れるために使う。 */
        val REALM: AttributeKey<String> = AttributeKey("EiaJwtRealm")

        /** RFC 6750 §2.1: `Bearer` の後に 1 つ以上の空白と token68。方式の名前は大文字小文字を区別しない(RFC 9110 §11.1)。 */
        private val BEARER = Regex("^[Bb][Ee][Aa][Rr][Ee][Rr] +([A-Za-z0-9\\-._~+/]+=*)$")
        private val SCHEME = Regex("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+")

        fun credential(header: String): Credential {
            BEARER.matchEntire(header)?.let { return Credential.Bearer(it.groupValues[1]) }
            val scheme = SCHEME.find(header)?.value
            return if (scheme != null && scheme.equals("Bearer", ignoreCase = true)) Credential.Invalid else Credential.Missing
        }
    }
}

/** [EiaJwtAuthenticationProvider] を登録する。 */
public fun AuthenticationConfig.eiaJwt(
    name: String? = null,
    configure: EiaJwtAuthenticationProvider.Config.() -> Unit,
) {
    register(EiaJwtAuthenticationProvider(EiaJwtAuthenticationProvider.Config(name).apply(configure)))
}

/** 検証を通ったトークン。認証の外で呼ぶと null。 */
public fun ApplicationCall.verifiedToken(): VerifiedToken? = principal<VerifiedToken>()
