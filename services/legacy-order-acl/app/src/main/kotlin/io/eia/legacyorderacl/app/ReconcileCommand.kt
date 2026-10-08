package io.eia.legacyorderacl.app

import io.eia.legacyorderacl.application.port.inbound.ReconciliationReport
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError
import io.eia.shared.kernel.ok
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.admin.Admin
import org.koin.dsl.koinApplication
import org.slf4j.LoggerFactory

/**
 * `legacy-order-acl reconcile [--dry-run]`: 照合を 1 回行い、結果を 1 行ずつ [output] に出す(ADR-0027。Runbook の手動の確認)。
 * 自動の再同期が有効なら、ずれを取り直す(定期の照合と同じ)。`--dry-run` なら比べるだけで、取り直さない。
 *
 * 終了コード: 0 = 一致 / 1 = ずれがある / 2 = 設定の誤り / 3 = 検査の失敗(取り込み・処理の待ちの超過、依存先の失敗)
 */
internal object ReconcileCommand {
    const val CONSISTENT = 0
    const val DRIFT = 1
    const val USAGE = 2
    const val FAILED = 3
    private val logger = LoggerFactory.getLogger(ReconcileCommand::class.java)

    fun run(
        env: Map<String, String>,
        dryRun: Boolean = false,
        output: (String) -> Unit,
    ): Int =
        when (val prepared = prepare(env)) {
            is Result.Err -> {
                USAGE.also { logger.error("設定が不正です: {}", prepared.error) }
            }

            is Result.Ok -> {
                val (config, password, observability) = prepared.value
                execute(config, password, observability, output, dryRun)
            }
        }

    private fun execute(
        config: AclConfig,
        password: ReconcileSecrets?,
        observability: ObservabilityConfig,
        output: (String) -> Unit,
        dryRun: Boolean,
    ): Int =
        Observability.init(observability).use { runtime ->
            val koin = koinApplication { modules(aclModule(config, runtime, committer = null, reconcileSecrets = password)) }.koin
            try {
                when (val result = runBlocking { koin.get<ReconcileJob>().runOnce(resync = !dryRun) }) {
                    is Result.Ok -> print(result.value, output)
                    is Result.Err -> FAILED.also { output("照合を終えられませんでした: ${result.error.message}") }
                }
            } finally {
                koin.getOrNull<Admin>()?.close()
                koin.get<HttpClient>().close()
                koin.close()
            }
        }

    /** 設定・パスワード・OTel の設定。誤りはログに出すメッセージで返す。 */
    private fun prepare(env: Map<String, String>): Result<Triple<AclConfig, ReconcileSecrets?, ObservabilityConfig>, String> =
        AclConfig
            .fromEnvironment(env)
            .mapError { it.message }
            .flatMap { config -> if (config.reconcile == null) err("${ReconcileConfig.DB_URL} が必要です") else ok(config) }
            .flatMap { config -> AclServer.reconcileSecrets(config, env).mapError { it.message }.map { config to it } }
            .flatMap { (config, password) ->
                ObservabilityConfig
                    .fromEnvironment(mapOf(ObservabilityConfig.ENV_SERVICE_NAME to "legacy-order-acl") + env)
                    .mapError { it.message }
                    .map { Triple(config, password, it) }
            }

    private fun print(
        report: ReconciliationReport,
        output: (String) -> Unit,
    ): Int {
        output(
            "position=${report.position} compared=${report.compared} drift=${report.drift.size} unconvertible=${report.unconvertible.size}",
        )
        output("digest=${report.digest}")
        report.drift.forEach { (key, kind) -> output("DRIFT $kind $key") }
        report.unconvertible.forEach { output("UNCONVERTIBLE $it") }
        return if (report.consistent) CONSISTENT else DRIFT
    }
}
