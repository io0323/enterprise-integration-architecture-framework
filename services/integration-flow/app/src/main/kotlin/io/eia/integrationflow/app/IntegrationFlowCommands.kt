package io.eia.integrationflow.app

import io.eia.integrationflow.adapters.flow.FlowSource
import io.eia.integrationflow.adapters.flow.YamlFlowDefinitions
import io.eia.integrationflow.application.usecase.LoadFlowsService
import io.eia.shared.kernel.Result
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * サブコマンドの振り分けと終了コード(0: 成功 / 2: 使い方・設定・Flow の定義の誤り)。
 *
 * - `validate`: Flow の定義を読み込み、起動時と同じ検証をして終わる(ADR-0036 §1)。Flow の YAML を足したときに、
 *   起動の前に確かめる。serve(P10 ⑤)も起動時に同じ検証をし、誤りがあれば起動しない。
 *
 * | 環境変数 | 既定 | 内容 |
 * |---|---|---|
 * | `INTEGRATION_FLOW_FLOWS_DIR` | なし(イメージに含めた contracts/flows) | Flow の定義(`*.flow.yaml`)を読むディレクトリ |
 */
internal object IntegrationFlowCommands {
    const val OK = 0
    const val USAGE = 2
    const val FLOWS_DIR = "INTEGRATION_FLOW_FLOWS_DIR"
    private val logger = LoggerFactory.getLogger(IntegrationFlowCommands::class.java)

    fun run(
        args: List<String>,
        env: Map<String, String>,
    ): Int =
        when (args.firstOrNull()) {
            "validate" -> {
                validate(env)
            }

            else -> {
                logger.error("使い方: integration-flow validate")
                USAGE
            }
        }

    /** Flow の定義の置き場(ディレクトリの指定がなければイメージに含めたもの)。 */
    fun source(env: Map<String, String>): FlowSource =
        env[FLOWS_DIR]?.takeIf { it.isNotBlank() }?.let { FlowSource.Directory(Path.of(it)) } ?: FlowSource.Classpath

    private fun validate(env: Map<String, String>): Int =
        when (val loaded = LoadFlowsService(YamlFlowDefinitions(source(env)))()) {
            is Result.Ok -> {
                loaded.value.flows.forEach { logger.info("Flow {}.v{} を読み込みました(canonical={})", it.id, it.version, it.canonical) }
                OK
            }

            is Result.Err -> {
                loaded.error.violations.forEach { logger.error("Flow の定義の誤り: {}: {}", it.field, it.reason) }
                USAGE
            }
        }
}
