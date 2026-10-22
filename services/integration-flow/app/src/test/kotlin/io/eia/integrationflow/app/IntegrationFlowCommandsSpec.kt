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

        test("validate: 追加のディレクトリの Flow を、イメージの Flow に足して読む。同じ ID が重なれば 2") {
            val dir = Files.createTempDirectory("extra-flows")
            try {
                val env = mapOf(IntegrationFlowConfig.EXTRA_FLOWS_DIR to dir.toString())
                IntegrationFlowConfig.flows(env) shouldBe FlowSource.Combined(listOf(FlowSource.Classpath, FlowSource.Directory(dir)))
                // 空の追加のディレクトリは、イメージの Flow だけ
                IntegrationFlowCommands.run(listOf("validate"), env) shouldBe IntegrationFlowCommands.OK
                val crm = checkNotNull(FlowSource::class.java.getResource("/contracts/flows/crm-customer.v1.flow.yaml")).readText()
                dir.resolve("crm-customer-copy.v1.flow.yaml").writeText(crm.replace("id: crm-customer", "id: crm-customer-copy"))
                IntegrationFlowCommands.run(listOf("validate"), env) shouldBe IntegrationFlowCommands.OK
                dir.resolve("crm-customer.v1.flow.yaml").writeText(crm)
                IntegrationFlowCommands.run(listOf("validate"), env) shouldBe IntegrationFlowCommands.USAGE
                (
                    FlowSource
                        .Combined(
                            listOf(FlowSource.Classpath, FlowSource.Directory(dir.resolve("missing"))),
                        ).files() is FlowSource.Result.Unreadable
                ) shouldBe
                    true
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        test("validate: ディレクトリを指定すると、そこの Flow を読む。誤りがあれば 2(起動しない)") {
            val dir = Files.createTempDirectory("flows")
            try {
                IntegrationFlowConfig.flows(mapOf(IntegrationFlowConfig.FLOWS_DIR to dir.toString())) shouldBe
                    FlowSource.Directory(dir)
                IntegrationFlowConfig.flows(mapOf(IntegrationFlowConfig.FLOWS_DIR to " ")) shouldBe FlowSource.Classpath
                // 空のディレクトリ
                IntegrationFlowCommands.run(listOf("validate"), mapOf(IntegrationFlowConfig.FLOWS_DIR to dir.toString())) shouldBe
                    IntegrationFlowCommands.USAGE
                dir.resolve("broken.v1.flow.yaml").writeText("id: broken\nversion: 1\n")
                IntegrationFlowCommands.run(listOf("validate"), mapOf(IntegrationFlowConfig.FLOWS_DIR to dir.toString())) shouldBe
                    IntegrationFlowCommands.USAGE
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
