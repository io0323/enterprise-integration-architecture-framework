package io.eia.order.app

import kotlin.system.exitProcess

/**
 * order-service のコマンド(ADR-0024 §2)。
 *
 * - `migrate`: DB の所有者の資格情報(`ORDER_DB_PASSWORD`)で、order と監査のマイグレーションを行って終わる。
 * - `serve`: アプリのロールの資格情報(`ORDER_APP_DB_PASSWORD`)だけで、リクエストを処理する。所有者のパスワードが環境にあれば起動しない。
 *
 * ローカル基盤と統合テストでは、`migrate` を実行してから `serve` を起動する。
 */
fun main(args: Array<String>) {
    val code = OrderCommands.run(args.toList(), System.getenv())
    if (code != 0) exitProcess(code)
}
