plugins {
    id("eia.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:kernel"))
        }
        commonTest.dependencies {
            implementation(libs.kotest.property)
        }
    }
}
