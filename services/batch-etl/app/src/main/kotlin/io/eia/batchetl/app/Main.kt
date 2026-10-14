package io.eia.batchetl.app

import kotlin.system.exitProcess

/**
 * batch-etl のコマンド(order-service と同じ形。ADR-0024 §2)。
 * - `migrate`: 所有者の資格情報でマイグレーションして終わる。
 * - `serve`: アプリのロールの資格情報だけで、日次の DAG の起動を始め、終了の合図(SIGTERM)まで動く。
 * - `run <yyyy-MM-dd> [--restart]`: 1 日分の日次の DAG を 1 回だけ実行して終わる(運用者の再実行。docs/runbooks/batch-rerun.md)。
 * - `reconcile <yyyy-MM-dd> [--restart]`: その日を実行の日として、週次の Full 照合を 1 回だけ行う。一致しない日があれば終了コード 3。
 * - `skip-lost-events [--confirm]`: 保持期間を過ぎて消えたイベントを飛ばし、取り込みを再開できるようにする(既定は表示だけ)。
 */
fun main(args: Array<String>) {
    exitProcess(BatchEtlCommands.run(args.toList(), System.getenv()))
}
