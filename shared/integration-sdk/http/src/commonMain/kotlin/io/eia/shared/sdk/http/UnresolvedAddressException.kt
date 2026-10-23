package io.eia.shared.sdk.http

/**
 * 名前解決の失敗。JVM の CIO は `IOException` ではない `java.nio.channels.UnresolvedAddressException` を投げるので、
 * 接続の失敗として別に捕まえる([HttpCallClassifier])。JVM 以外のエンジンは名前解決の失敗も `IOException` で投げるため、
 * JVM 以外の実体は投げられることのない型にする。
 */
internal expect class UnresolvedAddressException : IllegalArgumentException
