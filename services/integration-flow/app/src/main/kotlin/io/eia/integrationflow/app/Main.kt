package io.eia.integrationflow.app

import kotlin.system.exitProcess

/** integration-flow のコマンド(`migrate` / `serve` / `validate`)。 */
fun main(args: Array<String>) {
    exitProcess(IntegrationFlowCommands.run(args.toList(), System.getenv()))
}
