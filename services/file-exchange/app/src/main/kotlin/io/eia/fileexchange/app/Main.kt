package io.eia.fileexchange.app

import kotlin.system.exitProcess

/**
 * file-exchange のコマンド(order-service と同じ形。ADR-0024 §2)。
 * - `migrate`: 所有者の資格情報でマイグレーションして終わる。
 * - `serve`: アプリのロールの資格情報だけで、受け口のファイルの到着の監視と見回りを始め、終了の合図(SIGTERM)まで動く。
 * - `receive <名前> [--restart]`: 1 つのファイルの受け取りを 1 回だけ実行して終わる(運用者の再実行)。
 * - `sweep`: 受け口を 1 回だけ見回って終わる。
 * - `handoff-unsent [--days <日数>] [--execute]`: 取引先への送信を b2b-gateway に移す切り替え(P12 ⑦b)の 1 回きりの移行。送り終えていない
 *   ファイルを、受け渡しの場所に写す(既定は dry-run)。
 */
fun main(args: Array<String>) {
    exitProcess(FileExchangeCommands.run(args.toList(), System.getenv()))
}
