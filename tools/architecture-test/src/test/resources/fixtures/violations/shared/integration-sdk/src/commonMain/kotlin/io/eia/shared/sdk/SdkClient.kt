package io.eia.shared.sdk

// io.ktor.client は integration-sdk の例外として許可。io.ktor.server は違反
import io.ktor.client.HttpClient
import io.ktor.server.engine.EmbeddedServer

class SdkClient(
    val client: HttpClient,
)
