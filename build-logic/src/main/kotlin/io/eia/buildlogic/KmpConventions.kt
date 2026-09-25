package io.eia.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

internal const val JVM_TOOLCHAIN = 21

/** KMP モジュール共通の設定。既定ターゲットは [defaultTargets] で宣言し、各モジュールは `eiaTargets {}` で追加する。 */
internal fun Project.configureKmp(defaultTargets: EiaTargetsExtension.() -> Unit) {
    // kotest の KMP サポートは KSP を先に適用する必要がある
    pluginManager.apply("org.jetbrains.kotlin.multiplatform")
    pluginManager.apply("com.google.devtools.ksp")
    pluginManager.apply("io.kotest")
    pluginManager.apply("eia.quality")

    val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
    extensions.create<EiaTargetsExtension>("eiaTargets", kotlin).defaultTargets()

    kotlin.jvmToolchain(JVM_TOOLCHAIN)
    kotlin.sourceSets.getByName("commonTest").dependencies {
        implementation(libs.library("kotest-framework-engine"))
        implementation(libs.library("kotest-assertions-core"))
    }
    kotlin.sourceSets.getByName("jvmTest").dependencies {
        implementation(libs.library("kotest-runner-junit5"))
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
