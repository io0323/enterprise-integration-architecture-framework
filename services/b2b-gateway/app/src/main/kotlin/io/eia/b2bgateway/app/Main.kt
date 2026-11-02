package io.eia.b2bgateway.app

import kotlin.system.exitProcess

/**
 * b2b-gateway のコマンド(order-service と同じ形。ADR-0024 §2)。
 * - `migrate`: 所有者の資格情報でマイグレーションして終わる。
 * - `serve`: アプリのロールの資格情報だけで、受け取り・応答の送信・社内への取り込みを始め、終了の合図(SIGTERM)まで動く。
 * - `codes import <取引先> <種類> <CSV のファイル> --operator <運用者>`: 取引先のコードの対応表を、種類ごとに丸ごと入れ替えて終わる(ADR-0040 §6)。
 */
fun main(args: Array<String>) {
    exitProcess(B2bGatewayCommands.run(args.toList(), System.getenv()))
}
