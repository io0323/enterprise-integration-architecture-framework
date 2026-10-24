import io.eia.buildlogic.configureKmp
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// TCP のソケットを使う shared/* 用(shared/integration-sdk/mqtt。ADR-0038)。ターゲットは jvm, linuxX64, macosArm64。
// js は持たない(ブラウザからは TCP のソケットを開けない。ktor-network は js を持たない)。
configureKmp {
    jvm()
    native()
}

extensions.configure<KotlinMultiplatformExtension> {
    explicitApi()
}
