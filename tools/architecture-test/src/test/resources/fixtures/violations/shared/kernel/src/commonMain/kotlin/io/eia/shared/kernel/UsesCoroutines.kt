package io.eia.shared.kernel

// 違反: kernel は kotlin.* 以外に依存しない(ADR-0004 の禁止リストにないライブラリも許可リストで検出する)
import kotlinx.coroutines.delay

suspend fun pause() = delay(1)
