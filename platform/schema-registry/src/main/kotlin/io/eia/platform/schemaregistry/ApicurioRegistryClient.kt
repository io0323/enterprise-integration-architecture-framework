package io.eia.platform.schemaregistry

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.ok
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.channels.UnresolvedAddressException

/**
 * Apicurio Registry 3 の REST API(`/apis/registry/v3`)のクライアント(ADR-0025 §2)。
 *
 * - [findContentId]: スキーマの内容から contentId を引く(`POST /search/versions?canonical=true`)。公式の Serde が
 *   自動登録なしで ID を解決する方法と同じ。レジストリが正規化して比べるため、空白や項目の順序の違いは問わない。
 * - [schemaOf]: contentId からスキーマを取る(`GET /ids/contentIds/{id}`)。受信側が書き手のスキーマを得るのに使う。
 * - [register]: 契約のスキーマを登録する(`POST /groups/{group}/artifacts?ifExists=FIND_OR_CREATE_VERSION`)。
 *   同じ内容なら既存の版を返し、異なれば新しい版を作る。互換性ルール(FULL_TRANSITIVE)の違反は [SchemaRejected]。
 *   サービスは呼ばない(自動登録しない)。`tools/schema-publish`(`make schemas`)だけが使う。
 *
 * 1 回の要求は [SchemaRegistryConfig.requestTimeout] で打ち切る。リトライはしない(呼び出し側の [SchemaIdBook] が繰り返す)。
 * [httpClient] は呼び出し側が用意する。
 */
public class ApicurioRegistryClient(
    private val config: SchemaRegistryConfig,
    private val httpClient: HttpClient,
) {
    private val baseUrl = config.baseUrl.trimEnd('/')

    /** [subject] のスキーマと同じ内容の版の contentId。無効(DISABLED)の版は除き、最も新しい版のものを返す。 */
    public suspend fun findContentId(subject: SchemaSubject): Result<ContentId, SchemaRegistryError> =
        call {
            httpClient.post("$baseUrl/search/versions") {
                parameter("groupId", config.groupId)
                parameter("artifactId", subject.artifactId)
                parameter("artifactType", AVRO)
                parameter("canonical", true)
                parameter("orderby", "globalId")
                parameter("order", "desc")
                jsonBody(subject.schema)
            }
        }.flatMap { response ->
            when {
                response.status.isSuccess() -> {
                    parse<VersionSearchResults>(response).flatMap { results ->
                        results.versions
                            .firstOrNull { it.state != DISABLED }
                            ?.let { contentIdOf(it.contentId) }
                            ?: err(SchemaNotRegistered(subject.artifactId))
                    }
                }

                // アーティファクトもグループもない場合、版によっては 404 を返す
                response.status == HttpStatusCode.NotFound -> {
                    err(SchemaNotRegistered(subject.artifactId))
                }

                else -> {
                    unexpected(response)
                }
            }
        }

    /** [contentId] のスキーマの JSON。 */
    public suspend fun schemaOf(contentId: ContentId): Result<String, SchemaRegistryError> =
        call {
            httpClient.get("$baseUrl/ids/contentIds/${contentId.value}") { expectSuccess = false }
        }.flatMap { response ->
            when {
                response.status.isSuccess() -> ok(response.bodyAsText())
                response.status == HttpStatusCode.NotFound -> err(SchemaContentNotFound(contentId))
                else -> unexpected(response)
            }
        }

    /** [subject] を登録し、その内容の contentId を返す。同じ内容の版が既にあれば、新しい版を作らずにその ID を返す。 */
    public suspend fun register(subject: SchemaSubject): Result<ContentId, SchemaRegistryError> {
        val request =
            CreateArtifact(
                artifactId = subject.artifactId,
                artifactType = AVRO,
                firstVersion = CreateVersion(VersionContent(content = subject.schema, contentType = JSON_CONTENT_TYPE)),
            )
        return call {
            httpClient.post("$baseUrl/groups/${config.groupId.encodeURLPathPart()}/artifacts") {
                parameter("ifExists", "FIND_OR_CREATE_VERSION")
                parameter("canonical", true)
                jsonBody(json.encodeToString(CreateArtifact.serializer(), request))
            }
        }.flatMap { response ->
            when {
                response.status.isSuccess() -> {
                    parse<CreateArtifactResponse>(response).flatMap { contentIdOf(it.version.contentId) }
                }

                response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.Conflict -> {
                    val problem = parseProblem(response)
                    err(SchemaRejected(subject.artifactId, response.status.value, problem?.name, problem?.detail))
                }

                else -> {
                    unexpected(response)
                }
            }
        }
    }

    private fun HttpRequestBuilder.jsonBody(body: String) {
        expectSuccess = false
        contentType(ContentType.Application.Json)
        accept(ContentType.Application.Json)
        setBody(body)
    }

    /** 1 回の要求。タイムアウトと接続の失敗は [SchemaRegistryUnavailable] にする。応答の本文は [block] の中で読み切る。 */
    private suspend fun call(block: suspend () -> HttpResponse): Result<HttpResponse, SchemaRegistryError> =
        try {
            withTimeoutOrNull(config.requestTimeout) { ok(block()) } ?: err(SchemaRegistryUnavailable(TIMEOUT))
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            err(SchemaRegistryUnavailable(CONNECTION))
        } catch (_: UnresolvedAddressException) {
            err(SchemaRegistryUnavailable(CONNECTION))
        }

    private suspend inline fun <reified T> parse(response: HttpResponse): Result<T, SchemaRegistryError> =
        try {
            ok(json.decodeFromString<T>(response.bodyAsText()))
        } catch (_: SerializationException) {
            err(InvalidRegistryResponse("${T::class.simpleName} として読めません"))
        } catch (_: IllegalArgumentException) {
            err(InvalidRegistryResponse("${T::class.simpleName} として読めません"))
        }

    private suspend fun parseProblem(response: HttpResponse): RegistryProblem? =
        try {
            json.decodeFromString<RegistryProblem>(response.bodyAsText())
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun unexpected(response: HttpResponse): Result<Nothing, SchemaRegistryError> =
        if (response.status.value >= SERVER_ERROR) {
            err(SchemaRegistryUnavailable(SERVER_ERROR_REASON, response.status.value))
        } else {
            err(InvalidRegistryResponse("status=${response.status.value}"))
        }

    private fun contentIdOf(value: Long): Result<ContentId, SchemaRegistryError> =
        if (value in 0..Int.MAX_VALUE) ok(ContentId(value)) else err(InvalidRegistryResponse("contentId が範囲外です: $value"))

    @Serializable
    private data class VersionSearchResults(
        val versions: List<SearchedVersion> = emptyList(),
    )

    @Serializable
    private data class SearchedVersion(
        val contentId: Long,
        val state: String? = null,
    )

    @Serializable
    private data class CreateArtifact(
        val artifactId: String,
        val artifactType: String,
        val firstVersion: CreateVersion,
    )

    @Serializable
    private data class CreateVersion(
        val content: VersionContent,
    )

    @Serializable
    private data class VersionContent(
        val content: String,
        val contentType: String,
    )

    @Serializable
    private data class CreateArtifactResponse(
        val version: CreatedVersion,
    )

    @Serializable
    private data class CreatedVersion(
        val contentId: Long,
    )

    @Serializable
    private data class RegistryProblem(
        val name: String? = null,
        val detail: String? = null,
    )

    private companion object {
        const val AVRO = "AVRO"
        const val DISABLED = "DISABLED"
        const val JSON_CONTENT_TYPE = "application/json"
        const val TIMEOUT = "timeout"
        const val CONNECTION = "connection"
        const val SERVER_ERROR_REASON = "server_error"
        const val SERVER_ERROR = 500
        val json = Json { ignoreUnknownKeys = true }
    }
}
