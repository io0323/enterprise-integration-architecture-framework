// 候補の版は 2026-10-15 に Maven Central で確かめた最新の安定版。本体の libs.versions.toml には入れない(採用したものだけを ③ で入れる)
plugins {
    kotlin("jvm") version "2.4.20" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }
}
