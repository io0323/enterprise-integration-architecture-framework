plugins {
    id("eia.kmp-domain")
}

// BFF は業務のロジックを持たないので、domain のモジュールを置かない(ADR-0043 §8)。
kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":shared:kernel"))
    }
}
