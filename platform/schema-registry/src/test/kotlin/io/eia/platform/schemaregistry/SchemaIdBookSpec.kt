package io.eia.platform.schemaregistry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val OTHER = SchemaSubject("test.sample.deleted.v1", SCHEMA.replace("Sample", "Deleted"))

class SchemaIdBookSpec :
    FunSpec({
        test("解決するまで ready にならず、contentIdOf は SchemaIdsNotResolved を返す") {
            val book = SchemaIdBook(listOf(SUBJECT), FakeRegistry { respond("", HttpStatusCode.ServiceUnavailable) }.client())

            book.isReady shouldBe false
            book.contentIdOf(SUBJECT).err() shouldBe SchemaIdsNotResolved(SUBJECT.topic)
            book.resolve().err() shouldBe SchemaRegistryUnavailable("server_error", 503)
            book.isReady shouldBe false
            book.pendingTopics shouldBe setOf(SUBJECT.topic)
        }

        test("解決した後は、レジストリが止まっても contentIdOf はレジストリに問い合わせずに返す") {
            val down = AtomicBoolean(false)
            val registry =
                FakeRegistry { request ->
                    when {
                        down.get() -> respond("", HttpStatusCode.ServiceUnavailable)
                        String(request.body.toByteArray()) == SCHEMA -> json(searchResult(11L to "ENABLED"))
                        else -> json(searchResult(12L to "ENABLED"))
                    }
                }
            val book = SchemaIdBook(listOf(SUBJECT, OTHER), registry.client())

            book.resolve().ok()
            book.isReady shouldBe true
            down.set(true)
            val calls = registry.requests.size

            repeat(3) {
                book.contentIdOf(SUBJECT).ok() shouldBe ContentId(11)
                book.contentIdOf(OTHER).ok() shouldBe ContentId(12)
            }
            book.resolve().ok()
            registry.requests shouldHaveSize calls
        }

        test("一部だけ解決できた場合は、解決できたものを残し、次は残りだけを問い合わせる") {
            val registry =
                FakeRegistry { request ->
                    if (request.url.parameters["artifactId"] ==
                        SUBJECT.artifactId
                    ) {
                        json(searchResult(1L to "ENABLED"))
                    } else {
                        json(searchResult())
                    }
                }
            val book = SchemaIdBook(listOf(SUBJECT, OTHER), registry.client())

            book.resolve().err() shouldBe SchemaNotRegistered(OTHER.artifactId)
            book.contentIdOf(SUBJECT).ok() shouldBe ContentId(1)
            book.pendingTopics shouldBe setOf(OTHER.topic)

            book.resolve()
            registry.requests.map { it.url.parameters["artifactId"] } shouldBe
                listOf(SUBJECT.artifactId, OTHER.artifactId, OTHER.artifactId)
        }

        test("resolveUntilReady は解決するまで繰り返す(未登録も、後から登録されれば回復する)") {
            run {
                // 3 回目の問い合わせで登録済みになる(make schemas を後から実行した場合)
                var attempts = 0
                val registry =
                    FakeRegistry {
                        attempts++
                        json(if (attempts >= 3) searchResult(5L to "ENABLED") else searchResult())
                    }
                val book = SchemaIdBook(listOf(SUBJECT), registry.client())

                // MockEngine は実際のスレッドで応答するため、仮想時間ではなく短い間隔で実際に待つ
                withTimeout(10.seconds) { book.resolveUntilReady(retryInterval = 10.milliseconds) }

                book.isReady shouldBe true
                registry.requests shouldHaveSize 3
            }
        }

        test("登録したものと違うスキーマで contentIdOf を呼ぶのは実装の誤り(例外)。トピックの重複も拒否する") {
            val book = SchemaIdBook(listOf(SUBJECT), FakeRegistry { json(searchResult(1L to "ENABLED")) }.client())
            shouldThrow<IllegalArgumentException> { book.contentIdOf(SUBJECT.copy(schema = OTHER.schema)) }
            shouldThrow<IllegalArgumentException> {
                SchemaIdBook(listOf(SUBJECT, SUBJECT.copy(schema = OTHER.schema)), FakeRegistry { json("{}") }.client())
            }
        }

        test("WriterSchemas は取得したスキーマをキャッシュし、失敗はキャッシュしない") {
            var fail = true
            val registry = FakeRegistry { if (fail) respond("", HttpStatusCode.BadGateway) else json(SCHEMA) }
            val schemas = WriterSchemas(registry.client())

            schemas.schemaOf(ContentId(3)).err() shouldBe SchemaRegistryUnavailable("server_error", 502)
            fail = false
            schemas.schemaOf(ContentId(3)).ok() shouldBe SCHEMA
            schemas.schemaOf(ContentId(3)).ok() shouldBe SCHEMA
            registry.requests shouldHaveSize 2
        }
    })
