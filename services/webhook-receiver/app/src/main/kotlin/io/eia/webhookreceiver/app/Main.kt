package io.eia.webhookreceiver.app

import kotlin.system.exitProcess

/**
 * webhook-receiver のコマンド(order-service と同じ形。ADR-0024 §2)。
 * - `migrate`: 所有者の資格情報でマイグレーションして終わる。
 * - `serve`: アプリのロールの資格情報だけで、Webhook の受け口(mTLS)を始め、終了の合図(SIGTERM)まで動く。
 */
fun main(args: Array<String>) {
    exitProcess(WebhookReceiverCommands.run(args.toList(), System.getenv()))
}
