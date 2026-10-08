plugins {
    id("eia.kmp-domain")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":shared:kernel"))
        // レガシーの JST の現地時刻を UTC の Instant に変換する(ADR-0011 §8・ADR-0026 §8)
        implementation(libs.kotlinx.datetime)
    }
}
