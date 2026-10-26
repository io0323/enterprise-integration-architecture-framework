package io.eia.iotbridge.app

import kotlin.system.exitProcess

/** iot-bridge のコマンド(`serve` / `reprocess-dlq`)。 */
fun main(args: Array<String>) {
    exitProcess(IotBridgeCommands.run(args.toList(), System.getenv(), System.out))
}
