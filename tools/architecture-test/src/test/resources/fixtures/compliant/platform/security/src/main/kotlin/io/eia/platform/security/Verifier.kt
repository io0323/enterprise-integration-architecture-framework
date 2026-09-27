package io.eia.platform.security

// 準拠: platform/security の本番コードは Nimbus を使える(ADR-0019 §1)
import com.nimbusds.jwt.SignedJWT

class Verifier {
    fun parse(token: String): SignedJWT = SignedJWT.parse(token)
}
