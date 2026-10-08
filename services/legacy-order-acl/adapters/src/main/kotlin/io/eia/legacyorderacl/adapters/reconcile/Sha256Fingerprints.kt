package io.eia.legacyorderacl.adapters.reconcile

import io.eia.legacyorderacl.application.port.outbound.Fingerprints
import java.security.MessageDigest
import java.util.HexFormat

/** 正規の文字列(UTF-8)の SHA-256(小文字の 16 進)。 */
public object Sha256Fingerprints : Fingerprints {
    override fun sha256(canonical: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
}
