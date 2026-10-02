package io.eia.platform.schemaregistry

import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList

internal const val BASE_URL = "http://registry.test/apis/registry/v3"
internal const val SCHEMA: String =
    """{"type":"record","name":"Sample","namespace":"io.eia.events.test",""" +
        """"fields":[{"name":"id","type":"string"}]}"""
internal val SUBJECT = SchemaSubject("test.sample.created.v1", SCHEMA)

internal fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

internal fun <E> Result<*, E>.err(): E =
    when (this) {
        is Result.Ok -> fail("Err を期待しましたが Ok でした: $value")
        is Result.Err -> error
    }

/** Apicurio の代わり。受けた要求を記録し、[handler] で応答する。 */
internal class FakeRegistry(
    private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val requests: MutableList<HttpRequestData> = CopyOnWriteArrayList()

    val httpClient: HttpClient =
        HttpClient(
            MockEngine { request ->
                requests += request
                handler(request)
            },
        )

    fun client(config: SchemaRegistryConfig = SchemaRegistryConfig(BASE_URL)): ApicurioRegistryClient =
        ApicurioRegistryClient(config, httpClient)
}

internal fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

internal fun searchResult(vararg versions: Pair<Long, String>): String =
    versions.joinToString(prefix = """{"count":${versions.size},"versions":[""", postfix = "]}") { (id, state) ->
        """{"contentId":$id,"globalId":$id,"state":"$state","groupId":"default","artifactId":"x","version":"1"}"""
    }
