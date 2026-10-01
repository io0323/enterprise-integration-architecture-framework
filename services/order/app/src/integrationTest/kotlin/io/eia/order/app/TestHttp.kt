package io.eia.order.app

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.net.ssl.SSLContext

/**
 * テストの HTTP クライアント(JDK の HttpClient)。[sslContext] があれば、そのクライアント証明書で mTLS の接続をする。
 * ゲートウェイとの間と同じく HTTP/1.1 にする。
 */
internal class TestHttp(
    sslContext: SSLContext?,
) {
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .apply { sslContext?.let(::sslContext) }
            .build()

    fun get(
        url: String,
        token: String? = null,
    ): HttpResponse<String> = send(request(url, token).GET())

    fun post(
        url: String,
        token: String,
        idempotencyKey: String,
        body: String,
    ): HttpResponse<String> =
        send(
            request(url, token)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)),
        )

    private fun request(
        url: String,
        token: String?,
    ): HttpRequest.Builder =
        HttpRequest
            .newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .apply { token?.let { header("Authorization", "Bearer $it") } }

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        client.send(builder.build(), HttpResponse.BodyHandlers.ofString())

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}

internal fun HttpResponse<*>.header(name: String): String? = headers().firstValue(name).orElse(null)
