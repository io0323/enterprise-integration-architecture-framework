package io.eia.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalArgumentException("libs.versions.toml に library '$alias' がありません") }

internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).orElseThrow { IllegalArgumentException("libs.versions.toml に version '$alias' がありません") }.requiredVersion

/** CODING_STANDARDS のカバレッジ目標: domain / application は 90%、それ以外は 75%。 */
internal fun Project.coverageMinBound(): Int = if (name == "domain" || name == "application") 90 else 75

/** Kover の閾値を強制するか。P00 では警告のみ、P01 から gradle.properties で true にする。 */
internal fun Project.koverEnforced(): Boolean =
    providers.gradleProperty("eia.kover.enforce").map(String::toBoolean).getOrElse(false)
