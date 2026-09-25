package io.eia.platform.observability

import io.eia.shared.kernel.Result

class Tracer {
    fun start(name: String): Result<String, Nothing> = Result.Ok(name)
}
