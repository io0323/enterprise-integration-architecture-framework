package io.eia.legacyorderacl.app

import io.eia.shared.kernel.Result
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * legacy-order-acl のコマンド。
 *
 * - (引数なし)/ `serve`: 変換を動かし、終了の合図(SIGTERM)まで動く。照合が設定されていれば、定期的に照合する。設定の誤りは終了コード 2。
 * - `reconcile`: 照合を 1 回だけ行い、結果を出して終わる(Runbook の手動の確認。ADR-0027)。終了コードは [ReconcileCommand]。
 */
fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger("io.eia.legacyorderacl.app.Main")
    when (args.firstOrNull()) {
        null, "serve" -> {
            when (val server = AclServer.start(System.getenv())) {
                is Result.Ok -> {
                    server.value.awaitTermination()
                }

                is Result.Err -> {
                    logger.error("起動できません: {}", server.error.message)
                    exitProcess(ReconcileCommand.USAGE)
                }
            }
        }

        "reconcile" -> {
            exitProcess(ReconcileCommand.run(System.getenv()) { println(it) })
        }

        else -> {
            System.err.println("使い方: legacy-order-acl [serve | reconcile]")
            exitProcess(ReconcileCommand.USAGE)
        }
    }
}
