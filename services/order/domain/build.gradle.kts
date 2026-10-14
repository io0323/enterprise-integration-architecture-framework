plugins {
    id("eia.kmp-domain")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":shared:kernel"))
        // 業務の 1 日(日本時間)の区切り(ADR-0031 §1)
        implementation(libs.kotlinx.datetime)
    }
}
