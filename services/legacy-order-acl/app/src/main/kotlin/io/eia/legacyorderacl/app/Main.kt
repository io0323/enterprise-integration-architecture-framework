package io.eia.legacyorderacl.app

import io.eia.shared.kernel.Result
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/** legacy-order-acl を起動し、終了の合図(SIGTERM)まで動かす。設定の誤りは終了コード 2。 */
fun main() {
    when (val server = AclServer.start(System.getenv())) {
        is Result.Ok -> {
            server.value.awaitTermination()
        }

        is Result.Err -> {
            LoggerFactory.getLogger("io.eia.legacyorderacl.app.Main").error("起動できません: {}", server.error.message)
            exitProcess(2)
        }
    }
}
