package io.eia.legacysim.app

import kotlin.system.exitProcess

/**
 * レガシー基幹の模擬(legacy-sim。ADR-0026)のコマンド。
 *
 * - `migrate`: DB の所有者の資格情報(`LEGACY_SIM_DB_PASSWORD`)で、レガシーの表(V1)と DBA の CDC の設定(V2)を作って終わる。
 * - `simulate ...`: レガシーのアプリのロール(`LEGACY_SIM_APP_DB_PASSWORD`)で受注を書き換える。変えた受注番号を 1 行ずつ標準出力に出す。
 */
fun main(args: Array<String>) {
    val code = LegacySimCommands.run(args.toList(), System.getenv()) { println(it) }
    if (code != 0) exitProcess(code)
}
