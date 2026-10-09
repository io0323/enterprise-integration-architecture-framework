plugins {
    id("eia.kmp-domain")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":shared:kernel"))
    }
}
