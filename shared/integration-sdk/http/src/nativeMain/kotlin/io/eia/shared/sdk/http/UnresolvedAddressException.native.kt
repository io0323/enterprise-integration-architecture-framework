package io.eia.shared.sdk.http

/** JVM 以外のエンジンは名前解決の失敗を `IOException` で投げるため、この型は投げられない。 */
internal actual class UnresolvedAddressException : IllegalArgumentException()
