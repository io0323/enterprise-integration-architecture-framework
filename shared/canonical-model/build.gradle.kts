plugins {
    id("eia.kmp-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:kernel"))
            api(libs.kotlinx.serialization.json)
        }
    }
}
