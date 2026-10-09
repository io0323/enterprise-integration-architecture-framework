package io.eia.inventory.app

import kotlin.system.exitProcess

/**
 * inventory-service のコマンド(order-service と同じ形。ADR-0024 §2)。
 * - `migrate`: 所有者の資格情報でマイグレーションして終わる(compose の inventory-migrate)。
 * - `serve`: アプリのロールの資格情報だけで、コマンドの読み取りを始め、終了の合図(SIGTERM)まで動く。
 */
fun main(args: Array<String>) {
    exitProcess(InventoryCommands.run(args.toList(), System.getenv()))
}
