@file:Suppress("MagicNumber") // HTTP のステータスコード

package io.eia.platform.schemaregistry

import io.eia.platform.testsupport.ApicurioRegistryContainer
import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration.Companion.seconds

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private fun <E> Result<*, E>.err(): E =
    when (this) {
        is Result.Ok -> fail("Err を期待しましたが Ok でした: $value")
        is Result.Err -> error
    }

private val PRETTY_SCHEMA =
    """
    {
      "type": "record",
      "name": "Sample",
      "namespace": "io.eia.events.test",
      "fields": [
        { "name": "id", "type": "string" },
        { "name": "note", "type": ["null", "string"], "default": null }
      ]
    }
    """.trimIndent()

/** 実際の Apicurio Registry 3(images.env の版。FULL_TRANSITIVE)に対する検査(ADR-0025 §2・§3)。 */
class ApicurioRegistryClientIT :
    FunSpec({
        val registry = ApicurioRegistryContainer().also { it.start() }
        val http = HttpClient(CIO)
        val client = ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl, requestTimeout = 30.seconds), http)
        afterSpec {
            http.close()
            registry.stop()
        }

        test("登録・内容からの ID の解決・ID からの取得が一致する。空白と項目の順序の違いは同じ内容とみなす") {
            val subject = SchemaSubject("test.sample.created.v1", PRETTY_SCHEMA)
            val registered = client.register(subject).ok()

            client.register(subject).ok() shouldBe registered
            client.findContentId(subject).ok() shouldBe registered
            val minified = Json.parseToJsonElement(PRETTY_SCHEMA).toString()
            client.findContentId(subject.copy(schema = minified)).ok() shouldBe registered
            // 項目の属性の順序を変えても同じ(レジストリの正規化)
            val reordered =
                minified.replace(
                    "\"name\":\"Sample\",\"namespace\":\"io.eia.events.test\"",
                    "\"namespace\":\"io.eia.events.test\",\"name\":\"Sample\"",
                )
            client.findContentId(subject.copy(schema = reordered)).ok() shouldBe registered
            Json.parseToJsonElement(client.schemaOf(registered).ok()) shouldBe Json.parseToJsonElement(PRETTY_SCHEMA)
        }

        test("FULL 互換の変更(default 付きの項目の追加)は新しい版になり、互換性のない変更は SchemaRejected") {
            val subject = SchemaSubject("test.sample.updated.v1", PRETTY_SCHEMA)
            val v1 = client.register(subject).ok()
            val v2 =
                client
                    .register(
                        subject.copy(schema = withField(PRETTY_SCHEMA, """{"name":"extra","type":"string","default":"x"}""")),
                    ).ok()
            v2 shouldNotBe v1

            val rejected = client.register(subject.copy(schema = withField(PRETTY_SCHEMA, """{"name":"required","type":"string"}"""))).err()
            rejected.shouldBeInstanceOf<SchemaRejected>()
            rejected.status shouldBe 400
            rejected.name shouldBe "RuleViolationException"
        }

        test("未登録のトピック・別のトピックに登録した内容は SchemaNotRegistered") {
            client.register(SchemaSubject("test.sample.other.v1", PRETTY_SCHEMA)).ok()
            client.findContentId(SchemaSubject("test.sample.unknown.v1", PRETTY_SCHEMA)).err() shouldBe
                SchemaNotRegistered("test.sample.unknown.v1-value")
            client.schemaOf(ContentId(Int.MAX_VALUE.toLong())).err() shouldBe SchemaContentNotFound(ContentId(Int.MAX_VALUE.toLong()))
        }

        test("SchemaIdBook: 解決した後は、レジストリを止めても contentIdOf が答える。止まっている間の解決は Retryable の失敗") {
            val subject = SchemaSubject("test.sample.shipped.v1", PRETTY_SCHEMA)
            val registered = client.register(subject).ok()
            val book = SchemaIdBook(listOf(subject), client)
            book.resolve().ok()
            book.isReady shouldBe true

            registry.dockerClient.pauseContainerCmd(registry.containerId).exec()
            try {
                book.contentIdOf(subject).ok() shouldBe registered
                val unresolved =
                    SchemaIdBook(
                        listOf(subject),
                        ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl, requestTimeout = 1.seconds), http),
                    )
                unresolved.resolve().err() shouldBe SchemaRegistryUnavailable("timeout")
                unresolved.isReady shouldBe false
            } finally {
                registry.dockerClient.unpauseContainerCmd(registry.containerId).exec()
            }
        }
    })

private fun withField(
    schema: String,
    field: String,
): String {
    val root = Json.parseToJsonElement(schema).jsonObject
    val fields = JsonArray(root.getValue("fields").jsonArray + Json.parseToJsonElement(field))
    return JsonObject(root + ("fields" to fields)).toString()
}
