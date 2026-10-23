plugins {
    id("eia.kmp-library")
}

// KMP の連携クライアント SDK の HTTP の部分(ADR-0037)。Ktor Client の呼び出しの結果の分類・Retry-After・
// Client Credentials のトークンの取得とキャッシュ。JVM の platform(reliability・security)もこれを使う(同じ処理を 2 か所に持たない)。
// リトライ・Circuit Breaker・traceparent は shared/resilience、待ち時間の計算は kernel の RetryPolicy を使う。
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:resilience"))
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.cio)
        }
    }
}
