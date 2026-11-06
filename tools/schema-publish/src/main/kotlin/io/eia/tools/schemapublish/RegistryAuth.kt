package io.eia.tools.schemapublish

import io.eia.platform.security.secret.SecretName
import io.eia.platform.security.token.ClientCredentialsConfig
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import java.net.URI
import java.net.URISyntaxException

/**
 * secure profile の Apicurio の認証(ADR-0047 §9)。書き込みには Keycloak の client credentials のトークンが要る。
 *
 * 次の 3 つがそろっていれば、トークンを要求に付ける。どれもなければ付けない(認証のない Apicurio。普段の起動)。
 * 一部だけなら設定の誤り(黙って認証なしで送らない)。
 * - `SCHEMA_PUBLISH_TOKEN_URL`: トークンエンドポイント
 * - `SCHEMA_PUBLISH_CLIENT_ID`: クライアント(Keycloak の `eiaf-schema-publisher`。役割 sr-developer)
 * - `SCHEMA_PUBLISH_CLIENT_SECRET`(または `_FILE`): Client Secret(`SecretProvider` で読む)
 */
internal object RegistryAuth {
    const val TOKEN_URL = "SCHEMA_PUBLISH_TOKEN_URL"
    const val CLIENT_ID = "SCHEMA_PUBLISH_CLIENT_ID"
    val CLIENT_SECRET = SecretName("SCHEMA_PUBLISH_CLIENT_SECRET")

    sealed interface Setting {
        /** 認証しない。 */
        data object None : Setting

        data class Configured(
            val config: ClientCredentialsConfig,
        ) : Setting

        data class Invalid(
            val message: String,
        ) : Setting
    }

    fun setting(environment: Map<String, String>): Setting {
        val tokenUrl = environment[TOKEN_URL]?.takeIf { it.isNotBlank() }
        val clientId = environment[CLIENT_ID]?.takeIf { it.isNotBlank() }
        val secret = CLIENT_SECRET.value.let { environment.containsKey(it) || environment.containsKey(it + SecretName.FILE_SUFFIX) }
        return when {
            tokenUrl == null && clientId == null && !secret -> {
                Setting.None
            }

            tokenUrl == null || clientId == null || !secret -> {
                Setting.Invalid("$TOKEN_URL・$CLIENT_ID・$CLIENT_SECRET はそろえて指定してください")
            }

            else -> {
                // URI の構文の誤りと、http(s) の絶対 URL でないこと(ClientCredentialsConfig の検査)を、設定の誤りにする
                try {
                    Setting.Configured(ClientCredentialsConfig(URI(tokenUrl), clientId, CLIENT_SECRET))
                } catch (e: URISyntaxException) {
                    Setting.Invalid("$TOKEN_URL が不正です: ${e.message}")
                } catch (e: IllegalArgumentException) {
                    Setting.Invalid("$TOKEN_URL が不正です: ${e.message}")
                }
            }
        }
    }
}

/** 要求ごとに [authorization](`Bearer <token>`。取れなければ null)を `Authorization` に付ける。 */
internal fun bearerToken(authorization: suspend () -> String?): ClientPlugin<Unit> =
    createClientPlugin("RegistryBearerToken") {
        onRequest { request, _ ->
            authorization()?.let { request.headers[HttpHeaders.Authorization] = it }
        }
    }
