package io.eia.platform.security.jwt

import com.nimbusds.jwt.JWTClaimsSet
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.text.ParseException
import java.util.Date
import kotlin.time.Instant

/** 署名を確かめた JWT から取り出したクレーム(値の検証の前)。必須のクレーム(iss / aud / exp / iat)は必ずある。 */
internal class TokenClaims(
    val issuer: String,
    val audience: List<String>,
    val validity: Validity,
    val scope: String?,
    val caller: Caller,
    val tokenId: String?,
) {
    /** `iat` / `exp` / `nbf`。 */
    data class Validity(
        val issuedAt: Instant,
        val expiresAt: Instant,
        val notBefore: Instant?,
    )

    /** 呼び出し元: `sub` と `azp`(なければ `client_id`)。 */
    data class Caller(
        val subject: String?,
        val clientId: String?,
    )

    fun toVerifiedToken(): VerifiedToken =
        VerifiedToken(
            issuer = issuer,
            subject = caller.subject,
            clientId = caller.clientId,
            audience = audience,
            scopes =
                scope
                    ?.split(' ')
                    ?.filter(String::isNotEmpty)
                    ?.toSet()
                    .orEmpty(),
            issuedAt = validity.issuedAt,
            expiresAt = validity.expiresAt,
            tokenId = tokenId,
        )

    companion object {
        private const val SCOPE_CLAIM = "scope"
        private const val AZP_CLAIM = "azp"
        private const val CLIENT_ID_CLAIM = "client_id"

        /** クレームの型が違えば形式の不正、必須のクレームがなければ欠落として拒否する。 */
        fun read(claims: JWTClaimsSet): Result<TokenClaims, JwtVerificationError> =
            try {
                val issuer = claims.issuer
                val audience = claims.audience.orEmpty()
                val issuedAt = claims.issueTime?.toKotlinInstant()
                val expiresAt = claims.expirationTime?.toKotlinInstant()
                val validity =
                    if (issuedAt != null &&
                        expiresAt != null
                    ) {
                        Validity(issuedAt, expiresAt, claims.notBeforeTime?.toKotlinInstant())
                    } else {
                        null
                    }
                if (issuer == null || audience.isEmpty() || validity == null) {
                    err(JwtVerificationError.InvalidToken(JwtRejectionReason.MISSING_CLAIM))
                } else {
                    ok(
                        TokenClaims(
                            issuer = issuer,
                            audience = audience,
                            validity = validity,
                            scope = claims.getStringClaim(SCOPE_CLAIM),
                            caller = Caller(claims.subject, claims.getStringClaim(AZP_CLAIM) ?: claims.getStringClaim(CLIENT_ID_CLAIM)),
                            tokenId = claims.jwtid,
                        ),
                    )
                }
            } catch (_: ParseException) {
                err(JwtVerificationError.InvalidToken(JwtRejectionReason.MALFORMED))
            }

        private fun Date.toKotlinInstant(): Instant = Instant.fromEpochMilliseconds(time)
    }
}
