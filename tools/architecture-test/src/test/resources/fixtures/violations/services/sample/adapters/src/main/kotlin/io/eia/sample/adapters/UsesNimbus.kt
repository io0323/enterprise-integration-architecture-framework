package io.eia.sample.adapters

// 違反: adapters の本番コードで JWT を直接検証する(ADR-0019 §1。Nimbus は platform/security だけ)
import com.nimbusds.jwt.SignedJWT

class UsesNimbus {
    fun parse(token: String): SignedJWT = SignedJWT.parse(token)
}
