package io.eia.tools.dlqreplay

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(DlqReplayCommand().run(args.toList(), System.out))
}
