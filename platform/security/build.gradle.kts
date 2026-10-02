plugins {
    id("eia.jvm-library")
}

// JWT 検証(JWKS)・スコープ認可・Client Credentials のトークン取得・SecretProvider(Framework 12。ADR-0008・ADR-0019)。
// Nimbus JOSE+JWT を参照してよいのはこのモジュールだけ(Konsist の nimbusOnlyInSecurity)。
dependencies {
    api(project(":shared:kernel"))
    // トークン取得の Retry と Circuit Breaker、Retry-After の解析(ADR-0019 §4・ADR-0021)。platform 間の依存は Konsist で許可した一覧だけ
    api(project(":platform:reliability"))
    // 401 / 403 / 503 を Problem Details で返す(ADR-0019 §5・ADR-0022 §2)
    implementation(project(":platform:api"))
    api(libs.ktor.server.auth)
    api(libs.ktor.client.core)
    api(libs.opentelemetry.api)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.opentelemetry.sdk.testing)
    // ログとエラーのメッセージに鍵・トークンが出ないことを、② のマスキング(EiaLogEncoder)と組み合わせて確かめる
    testImplementation(project(":platform:observability"))

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.ktor.client.cio)
    integrationTestImplementation(libs.testcontainers.toxiproxy)
}

// JWKS の取得をどの dispatcher で行うかの計測(P05 ⑨。docs/reports/p05-jwks-dispatcher.md)。
// 待ち時間の数値で判断する計測なので、build と CI には含めない(Issue #46)。`./gradlew :platform:security:measureJwksDispatcher` で手動で実行する。
// コンパイルだけは check に含め、計測のコードが壊れたままにならないようにする。
val measurement: SourceSet = sourceSets.create("measurement")
configurations.named(measurement.implementationConfigurationName) { extendsFrom(configurations.testImplementation.get()) }
configurations.named(measurement.runtimeOnlyConfigurationName) { extendsFrom(configurations.testRuntimeOnly.get()) }
kotlin.target.compilations.run {
    // JwtVerifier の internal(verifyBlocking)と、テストの鍵・トークンの部品(TestTokens)を使う
    getByName("measurement").associateWith(getByName("main"))
    getByName("measurement").associateWith(getByName("test"))
}
kover {
    currentProject {
        sources { excludedSourceSets.add(measurement.name) }
    }
}
tasks.named("check") { dependsOn(tasks.named(measurement.classesTaskName)) }
tasks.register<JavaExec>("measureJwksDispatcher") {
    description = "JWKS の取得を待つ間に、共有の Dispatchers.IO の DB の処理が待たされるかを、dispatcher の方式ごとに計測する(手動)。"
    group = "measurement"
    classpath = measurement.runtimeClasspath
    mainClass.set("io.eia.platform.security.jwt.JwksDispatcherMeasurementKt")
    // 方式 D(仮想スレッド)で、JWKS の取得の経路にピン留めがないかを確かめる(JDK 21)
    jvmArgs("-Djdk.tracePinnedThreads=full")
    // 条件(-Pjwks.verifications=200 など)。構成キャッシュが値の変化を追えるよう、項目ごとに providers で読む
    listOf("verifications", "dbTasks", "dbWorkMs", "repeat").forEach { key ->
        providers.gradleProperty("jwks.$key").orNull?.let { systemProperty("jwks.$key", it) }
    }
}
