package io.eia.b2bgateway.adapters.archive

import java.security.MessageDigest
import java.util.HexFormat

/** SHA-256(小文字の 16 進数。監査の payload_sha256 と同じ形)。 */
internal fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
