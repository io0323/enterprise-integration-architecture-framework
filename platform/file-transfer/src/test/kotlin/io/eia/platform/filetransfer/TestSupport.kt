package io.eia.platform.filetransfer

import io.eia.shared.kernel.Result

internal fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> error("Ok を期待しましたが Err でした: $error")
    }

internal fun <E> Result<*, E>.err(): E =
    when (this) {
        is Result.Ok -> error("Err を期待しましたが Ok でした: $value")
        is Result.Err -> error
    }
