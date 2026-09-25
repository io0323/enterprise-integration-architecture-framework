import io.eia.buildlogic.configureKmp
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// shared/* 用。既定ターゲットは jvm, js(IR), linuxX64, macosArm64(ADR-0004)。
configureKmp { all() }

extensions.configure<KotlinMultiplatformExtension> {
    explicitApi()
}
