package io.eia.shared.kernel

import io.kotest.assertions.fail

fun <T> Result<T, *>.shouldBeOk(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

fun <E> Result<*, E>.shouldBeErr(): E =
    when (this) {
        is Result.Ok -> fail("Err を期待しましたが Ok でした: $value")
        is Result.Err -> error
    }
