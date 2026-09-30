plugins {
    id("eia.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:kernel"))
            // Timeout / Retry の待機 / Circuit Breaker の Mutex / Bulkhead の Semaphore(ADR-0021)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotest.property)
            // 仮想時間(kotest の coroutineTestScope)。実時間の sleep に頼らない
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
