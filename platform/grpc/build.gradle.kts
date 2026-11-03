plugins {
    id("eia.jvm-library")
}

// 社内の gRPC の共通部品(mTLS・締め切り・trace / Correlation ID の伝搬・JWT・状態コードの写し方。ADR-0042)。
// スタブは platform/grpc-stubs にあり、ここでは使わない(テストの題材にだけ使う)。
dependencies {
    api(project(":shared:kernel"))
    api(project(":shared:resilience"))
    api(project(":platform:security"))
    api(project(":platform:observability"))
    api(libs.grpc.api)
    api(libs.grpc.stub)
    api(libs.grpc.kotlin.stub)
    // Netty は内包の版を使う(Ktor の Netty と版がぶつからない。ADR-0042 §5)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(project(":platform:grpc-stubs"))
    testImplementation(project(":platform:test-support"))
    testImplementation(project(":platform:reliability"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.opentelemetry.sdk.testing)
}
