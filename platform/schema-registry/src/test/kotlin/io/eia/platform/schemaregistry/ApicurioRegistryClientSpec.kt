package io.eia.platform.schemaregistry

import io.eia.shared.kernel.DomainError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ApicurioRegistryClientSpec :
    FunSpec({
        context("findContentId") {
            test("スキーマの内容で検索し、最も新しい有効な版の contentId を返す") {
                val registry = FakeRegistry { json(searchResult(9L to "DISABLED", 7L to "ENABLED", 3L to "ENABLED")) }

                registry.client().findContentId(SUBJECT).ok() shouldBe ContentId(7)

                val request = registry.requests.single()
                request.method shouldBe HttpMethod.Post
                request.url.encodedPath shouldBe "/apis/registry/v3/search/versions"
                request.url.parameters["groupId"] shouldBe "default"
                request.url.parameters["artifactId"] shouldBe "test.sample.created.v1-value"
                request.url.parameters["artifactType"] shouldBe "AVRO"
                request.url.parameters["canonical"] shouldBe "true"
                request.url.parameters["orderby"] shouldBe "globalId"
                request.url.parameters["order"] shouldBe "desc"
                String(request.body.toByteArray()) shouldBe SCHEMA
            }

            test("見つからなければ SchemaNotRegistered(版がない・アーティファクトがない 404 のどちらも)") {
                FakeRegistry { json(searchResult()) }.client().findContentId(SUBJECT).err() shouldBe
                    SchemaNotRegistered("test.sample.created.v1-value")
                FakeRegistry { json(searchResult(1L to "DISABLED")) }.client().findContentId(SUBJECT).err() shouldBe
                    SchemaNotRegistered("test.sample.created.v1-value")
                FakeRegistry { json("{}", HttpStatusCode.NotFound) }.client().findContentId(SUBJECT).err() shouldBe
                    SchemaNotRegistered("test.sample.created.v1-value")
            }

            test("5xx は一時的な失敗(Retryable)、ほかの想定外のステータスと形式の不正は NonRetryable") {
                val unavailable = FakeRegistry { respond("", HttpStatusCode.ServiceUnavailable) }.client().findContentId(SUBJECT).err()
                unavailable shouldBe SchemaRegistryUnavailable("server_error", 503)
                unavailable.asDomainError().shouldBeInstanceOf<DomainError.Retryable>()

                FakeRegistry { respond("", HttpStatusCode.Forbidden) }.client().findContentId(SUBJECT).err() shouldBe
                    InvalidRegistryResponse("status=403")
                val invalid = FakeRegistry { json("""{"versions":"x"}""") }.client().findContentId(SUBJECT).err()
                invalid.shouldBeInstanceOf<InvalidRegistryResponse>()
                invalid.asDomainError().shouldBeInstanceOf<DomainError.NonRetryable>()
            }

            test("接続の失敗とタイムアウトは SchemaRegistryUnavailable") {
                FakeRegistry { throw IOException("connection refused") }.client().findContentId(SUBJECT).err() shouldBe
                    SchemaRegistryUnavailable("connection")
                FakeRegistry {
                    delay(5.seconds)
                    json(searchResult(1L to "ENABLED"))
                }.client(SchemaRegistryConfig(BASE_URL, requestTimeout = 50.milliseconds)).findContentId(SUBJECT).err() shouldBe
                    SchemaRegistryUnavailable("timeout")
            }

            test("4 バイトに収まらない contentId は応答の不正にする") {
                FakeRegistry { json(searchResult(Int.MAX_VALUE.toLong() + 1 to "ENABLED")) }
                    .client()
                    .findContentId(SUBJECT)
                    .err()
                    .shouldBeInstanceOf<InvalidRegistryResponse>()
            }
        }

        context("schemaOf") {
            test("contentId のスキーマを返す。なければ SchemaContentNotFound") {
                val registry =
                    FakeRegistry { request ->
                        if (request.url.encodedPath.endsWith("/7")) json(SCHEMA) else json("{}", HttpStatusCode.NotFound)
                    }

                registry.client().schemaOf(ContentId(7)).ok() shouldBe SCHEMA
                registry.requests
                    .single()
                    .url.encodedPath shouldBe "/apis/registry/v3/ids/contentIds/7"
                registry.client().schemaOf(ContentId(8)).err() shouldBe SchemaContentNotFound(ContentId(8))
            }
        }

        context("register") {
            test("既存の版があれば返し、なければ作る(ifExists=FIND_OR_CREATE_VERSION)。本文に契約のスキーマをそのまま入れる") {
                val registry =
                    FakeRegistry { json("""{"artifact":{"artifactId":"x"},"version":{"globalId":4,"contentId":2,"version":"1"}}""") }

                registry.client().register(SUBJECT).ok() shouldBe ContentId(2)

                val request = registry.requests.single()
                request.url.encodedPath shouldBe "/apis/registry/v3/groups/default/artifacts"
                request.url.parameters["ifExists"] shouldBe "FIND_OR_CREATE_VERSION"
                request.url.parameters["canonical"] shouldBe "true"
                val body = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
                body["artifactId"]!!.jsonPrimitive.content shouldBe "test.sample.created.v1-value"
                body["artifactType"]!!.jsonPrimitive.content shouldBe "AVRO"
                val content = body["firstVersion"]!!.jsonObject["content"]!!.jsonObject
                content["content"]!!.jsonPrimitive.content shouldBe SCHEMA
                content["contentType"]!!.jsonPrimitive.content shouldBe "application/json"
            }

            test("互換性ルールの違反(400)は SchemaRejected。レジストリの説明を残し、スキーマの全文は入れない") {
                val rejected =
                    FakeRegistry {
                        json(
                            """{"status":400,"name":"RuleViolationException",""" +
                                """"title":"Incompatible artifact","detail":"extra at /fields/1"}""",
                            HttpStatusCode.BadRequest,
                        )
                    }.client().register(SUBJECT).err()

                rejected shouldBe SchemaRejected("test.sample.created.v1-value", 400, "RuleViolationException", "extra at /fields/1")
                rejected.message shouldContain "extra at /fields/1"
                rejected.message shouldNotContain SCHEMA
                rejected.asDomainError().shouldBeInstanceOf<DomainError.NonRetryable>()
            }
        }

        test("SchemaSubject の toString にスキーマの全文を出さない") {
            SUBJECT.toString() shouldBe "SchemaSubject(topic=test.sample.created.v1)"
        }
    })
