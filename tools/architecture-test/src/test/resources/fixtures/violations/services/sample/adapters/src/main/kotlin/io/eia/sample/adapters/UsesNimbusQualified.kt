package io.eia.sample.adapters

// 違反: import せずに完全修飾名で Nimbus を使う(ADR-0019 §1)
class UsesNimbusQualified {
    fun parse(token: String): Any = com.nimbusds.jwt.SignedJWT.parse(token)
}
