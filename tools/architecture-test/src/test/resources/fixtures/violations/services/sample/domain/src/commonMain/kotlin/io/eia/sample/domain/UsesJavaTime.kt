package io.eia.sample.domain

// 違反: commonMain で java.* / kotlin.jvm.*(JvmInline 以外)を import
import java.time.Instant
import kotlin.jvm.Synchronized

class UsesJavaTime(
    val at: Instant,
)
