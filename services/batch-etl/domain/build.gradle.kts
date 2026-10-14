plugins {
    id("eia.kmp-domain")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":shared:kernel"))
        // 業務の日(日本時間)の区切り(ADR-0031 §1)
        api(libs.kotlinx.datetime)
    }
}
