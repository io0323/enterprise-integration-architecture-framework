package io.eia.bffgraphql.app

import kotlin.system.exitProcess

/** bff-graphql のコマンド。`serve`: 終了の合図(SIGTERM)まで動く。データを持たないので migrate はない。 */
fun main(args: Array<String>) {
    exitProcess(BffCommands.run(args.toList(), System.getenv()))
}
