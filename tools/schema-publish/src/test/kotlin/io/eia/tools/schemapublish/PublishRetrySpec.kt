package io.eia.tools.schemapublish

import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.SchemaSubject
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.time.Duration

private val SUBJECT =
    SchemaSubject("sales.test.created.v1", """{"type":"record","name":"Created","namespace":"io.eia.events.sales","fields":[]}""")
private const val CREATED = """{"version":{"contentId":5}}"""

/** [responses] を順に返す(例外なら接続の失敗)。要求の数を数える。 */
private class Registry(
    private val responses: List<Any>,
) {
    var requests = 0

    val client =
        ApicurioRegistryClient(
            SchemaRegistryConfig("http://registry.test/apis/registry/v3"),
            HttpClient(
                MockEngine {
                    when (val next = responses[requests++]) {
                        is IOException -> {
                            throw next
                        }

                        is HttpStatusCode -> {
                            respond(
                                if (next.value <
                                    300
                                ) {
                                    CREATED
                                } else {
                                    """{"name":"x","detail":"y"}"""
                                },
                                next,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        else -> {
                            error("unexpected")
                        }
                    }
                },
            ),
        )
}

class PublishRetrySpec :
    FunSpec({
        test("起動の直後で接続できない・5xx のときは、待ってから再試行して登録する") {
            val registry = Registry(listOf(IOException("connection refused"), HttpStatusCode.ServiceUnavailable, HttpStatusCode.OK))
            val waits = mutableListOf<Duration>()
            val out = mutableListOf<String>()

            publish(listOf(SUBJECT), registry.client, out::add, sleep = { waits += it }) shouldBe ExitCode.OK

            registry.requests shouldBe 3
            waits shouldBe listOf(RETRY.initialDelay, RETRY.initialDelay * 2)
            out.last() shouldBe "OK   sales.test.created.v1-value contentId=5"
        }

        test("互換性の違反などの拒否は再試行しない") {
            val registry = Registry(listOf(HttpStatusCode.Conflict))
            publish(listOf(SUBJECT), registry.client, {}, sleep = { error("待たない") }) shouldBe ExitCode.REJECTED
            registry.requests shouldBe 1
        }

        test("回数の上限まで接続できなければ、実行できない(終了コード 2)") {
            val registry = Registry(List(RETRY.maxAttempts) { IOException("connection refused") })
            publish(listOf(SUBJECT), registry.client, {}, sleep = {}) shouldBe ExitCode.CANNOT_RUN
            registry.requests shouldBe RETRY.maxAttempts
        }
    })
