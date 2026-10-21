package io.eia.integrationflow.app

import kotlin.system.exitProcess

/** integration-flow のコマンド。P10 ④ は `validate`(Flow の定義の検証)だけ。 */
fun main(args: Array<String>) {
    exitProcess(IntegrationFlowCommands.run(args.toList(), System.getenv()))
}
