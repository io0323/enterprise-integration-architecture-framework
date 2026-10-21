package io.eia.integrationflow.app

import io.eia.integrationflow.adapters.flow.FlowSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.writeText

class IntegrationFlowCommandsSpec :
    FunSpec({
        test("validate: イメージに含めた Flow の定義は検証を通る(0)。使い方の誤りは 2") {
            IntegrationFlowCommands.run(listOf("validate"), emptyMap()) shouldBe IntegrationFlowCommands.OK
            IntegrationFlowCommands.run(emptyList(), emptyMap()) shouldBe IntegrationFlowCommands.USAGE
            IntegrationFlowCommands.run(listOf("serve"), emptyMap()) shouldBe IntegrationFlowCommands.USAGE
        }

        test("validate: ディレクトリを指定すると、そこの Flow を読む。誤りがあれば 2(起動しない)") {
            val dir = Files.createTempDirectory("flows")
            try {
                IntegrationFlowCommands.source(mapOf(IntegrationFlowCommands.FLOWS_DIR to dir.toString())) shouldBe
                    FlowSource.Directory(dir)
                IntegrationFlowCommands.source(mapOf(IntegrationFlowCommands.FLOWS_DIR to " ")) shouldBe FlowSource.Classpath
                // 空のディレクトリ
                IntegrationFlowCommands.run(listOf("validate"), mapOf(IntegrationFlowCommands.FLOWS_DIR to dir.toString())) shouldBe
                    IntegrationFlowCommands.USAGE
                dir.resolve("broken.v1.flow.yaml").writeText("id: broken\nversion: 1\n")
                IntegrationFlowCommands.run(listOf("validate"), mapOf(IntegrationFlowCommands.FLOWS_DIR to dir.toString())) shouldBe
                    IntegrationFlowCommands.USAGE
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
