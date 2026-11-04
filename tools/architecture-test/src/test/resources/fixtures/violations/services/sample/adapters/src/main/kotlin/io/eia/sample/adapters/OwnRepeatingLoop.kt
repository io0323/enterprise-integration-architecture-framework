package io.eia.sample.adapters

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 違反サンプル: RepeatingJobs を使わない自前の繰り返し。 */
class OwnRepeatingLoop {
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            delay(1000)
        }
    }
}
