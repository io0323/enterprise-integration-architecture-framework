plugins {
    id("eia.kmp-library")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotest.property)
        }
    }
}
