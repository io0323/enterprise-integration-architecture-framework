package io.eia.tools.contract

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

private val json = JsonMapper.builder().build()

private val VALID =
    """
    {
      "file": "sales_daily_20260925010000_001.parquet",
      "recordCount": 1200,
      "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
      "schemaVersion": "sales_daily.v1",
      "createdAt": "2026-09-25T01:00:00Z",
      "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
      "correlationId": "c-001",
      "unknownField": "未知の項目は無視する"
    }
    """.trimIndent()

class ManifestSchemaSpec :
    FunSpec({
        val schema =
            JsonSchemas.schemaOf(
                json.readTree(Workspace.REPOSITORY_ROOT.resolve("contracts/files/manifest.v1.schema.json").toFile()),
            )

        test("標準の manifest は適合する(未知の項目は許す)") {
            JsonSchemas.validate(schema, json.readTree(VALID)).shouldBeEmpty()
        }

        mapOf<String, (ObjectNode) -> Unit>(
            "sha256 の欠落" to { it.remove("sha256") },
            "traceparent の欠落" to { it.remove("traceparent") },
            "correlationId の欠落" to { it.remove("correlationId") },
            "sha256 が 16 進 64 桁でない" to { it.put("sha256", "abc") },
            "recordCount が負" to { it.put("recordCount", -1) },
            "createdAt が UTC(Z)でない" to { it.put("createdAt", "2026-09-25T10:00:00+09:00") },
            "file が命名規約外" to { it.put("file", "SalesDaily.parquet") },
            "schemaVersion が {system}_{dataset}.v{n} でない" to { it.put("schemaVersion", "1.0") },
        ).forEach { (name, mutate) ->
            test("違反: $name") {
                val manifest = json.readTree(VALID) as ObjectNode
                mutate(manifest)

                JsonSchemas.validate(schema, manifest).shouldNotBeEmpty()
            }
        }
    })
