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

/**
 * 統合テスト(`integrationTest`)で実行された本番コードも、Kover のカバレッジに数えるか(Issue #56。MODULE_DESIGN §5)。
 * CI の `integration` ジョブで `-Peia.kover.withIntegrationTests=true` を付けて `koverVerify` を実行する。
 * 既定(`./gradlew build`)は false で、統合テストを build に巻き込まない。
 */
internal fun Project.koverWithIntegrationTests(): Boolean =
    providers.gradleProperty("eia.kover.withIntegrationTests").map(String::toBoolean).getOrElse(false)

/** 統合テストを持つモジュールか(`src/integrationTest` がある)。 */
internal fun Project.hasIntegrationTests(): Boolean = layout.projectDirectory.dir("src/integrationTest").asFile.exists()

/** Kover の閾値を強制するか。P00 では警告のみ、P01 から gradle.properties で true にする。 */
internal fun Project.koverEnforced(): Boolean =
    providers.gradleProperty("eia.kover.enforce").map(String::toBoolean).getOrElse(false)
