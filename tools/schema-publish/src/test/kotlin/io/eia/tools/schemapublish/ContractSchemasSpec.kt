package io.eia.tools.schemapublish

import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.SchemaSubject
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText

private val CONTRACTS: Path = Path(checkNotNull(System.getProperty("eia.contractsDir")) { "Gradle の test タスクから実行してください" })

private fun File.write(
    path: String,
    text: String,
) = resolve(path).apply { parentFile.mkdirs() }.writeText(text.trimIndent())

private const val AVSC = """{"type":"record","name":"A","namespace":"io.eia.events.test","fields":[{"name":"id","type":"string"}]}"""

class ContractSchemasSpec :
    FunSpec({
        test("リポジトリの AsyncAPI のチャネルごとに、トピック名と参照先の Avro スキーマの組を集める") {
            val subjects = ContractSchemas.load(CONTRACTS)

            subjects shouldContain SchemaSubject("sales.order.created.v1", CONTRACTS.resolve("avro/sales/OrderCreated.avsc").readText())
            subjects shouldContain SchemaSubject("sales.order.cancelled.v1", CONTRACTS.resolve("avro/sales/OrderCancelled.avsc").readText())
        }

        test("文書内の \$ref を何段でも辿る") {
            val root = tempdir()
            root.write("avro/a.avsc", AVSC)
            root.write(
                "asyncapi/test.v1.yaml",
                """
                asyncapi: 3.0.0
                channels:
                  a:
                    ${'$'}ref: "#/components/channels/A"
                components:
                  channels:
                    A:
                      address: test.thing.happened.v1
                      messages:
                        m:
                          ${'$'}ref: "#/components/messages/M"
                  messages:
                    M:
                      payload:
                        ${'$'}ref: "#/components/payloads/P"
                  payloads:
                    P:
                      schemaFormat: application/vnd.apache.avro+json;version=1.9.0
                      schema:
                        ${'$'}ref: "../avro/a.avsc"
                """,
            )

            ContractSchemas.load(root.toPath()) shouldBe listOf(SchemaSubject("test.thing.happened.v1", AVSC))
        }

        test("1 つのチャネルに別々のスキーマのメッセージがあれば拒否する(トピックごとにアーティファクトは 1 つ)") {
            val root = tempdir()
            root.write("avro/a.avsc", AVSC)
            root.write("avro/b.avsc", AVSC.replace("\"A\"", "\"B\""))
            root.write(
                "asyncapi/test.v1.yaml",
                """
                channels:
                  a:
                    address: test.thing.happened.v1
                    messages:
                      one: { payload: { schema: { ${'$'}ref: "../avro/a.avsc" } } }
                      two: { payload: { schema: { ${'$'}ref: "../avro/b.avsc" } } }
                """,
            )

            shouldThrow<IllegalArgumentException> { ContractSchemas.load(root.toPath()) }.message shouldContain "1 つにしてください"
        }

        test("Avro のスキーマを AsyncAPI の中に直接書いたもの・参照先のないものは拒否する") {
            val inline = tempdir()
            inline.write(
                "asyncapi/test.v1.yaml",
                """
                channels:
                  a:
                    address: test.thing.happened.v1
                    messages:
                      one: { payload: { schema: { type: record } } }
                """,
            )
            shouldThrow<IllegalStateException> { ContractSchemas.load(inline.toPath()) }

            val missing = tempdir()
            missing.write(
                "asyncapi/test.v1.yaml",
                """
                channels:
                  a:
                    address: test.thing.happened.v1
                    messages:
                      one: { payload: { schema: { ${'$'}ref: "../avro/none.avsc" } } }
                """,
            )
            shouldThrow<IllegalArgumentException> { ContractSchemas.load(missing.toPath()) }.message shouldContain "がありません"
        }

        test("終了コード: すべて成功 0、拒否だけなら 1、接続できない・応答の不正を含めば 2") {
            val subjects = listOf(SchemaSubject("test.a.happened.v1", AVSC), SchemaSubject("test.b.happened.v1", AVSC))

            suspend fun exitCode(response: () -> Pair<HttpStatusCode, String>): Int {
                val http =
                    HttpClient(
                        MockEngine {
                            val (code, body) = response()
                            respond(body, code, headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    )
                val lines = mutableListOf<String>()
                return publish(
                    subjects,
                    ApicurioRegistryClient(SchemaRegistryConfig("http://registry.test/apis/registry/v3"), http),
                    lines::add,
                )
            }
            val created = HttpStatusCode.OK to """{"version":{"contentId":1}}"""
            val rejected = HttpStatusCode.BadRequest to """{"name":"RuleViolationException","detail":"x"}"""

            exitCode { created } shouldBe ExitCode.OK
            exitCode { rejected } shouldBe ExitCode.REJECTED
            exitCode { HttpStatusCode.ServiceUnavailable to "" } shouldBe ExitCode.CANNOT_RUN
        }

        test("引数: --registry は必須。未知の引数は拒否する") {
            Options.parse(listOf("--registry", "http://r/apis/registry/v3")) shouldBe Options("http://r/apis/registry/v3", Path("."))
            Options.parse(listOf("--root", "/x")) shouldBe null
            Options.parse(listOf("--registry", "http://r", "--other", "x")) shouldBe null
            Options.parse(listOf("--registry")) shouldBe null
        }
    })
