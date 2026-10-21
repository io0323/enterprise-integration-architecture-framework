package io.eia.saasmock.app

import io.eia.shared.kernel.Result
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/** SaaS(CRM)の模擬を起動し、終了の合図(SIGTERM)まで動く。設定の誤りは理由を出して 2 で終える。 */
fun main() {
    when (val started = SaasMockServer.start(System.getenv())) {
        is Result.Ok -> {
            started.value.awaitTermination()
        }

        is Result.Err -> {
            LoggerFactory.getLogger("io.eia.saasmock.app.Main").error("設定が不正です: {}", started.error.message)
            exitProcess(2)
        }
    }
}
