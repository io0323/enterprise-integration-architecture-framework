plugins {
    id("eia.jvm-library")
}

// 冪等消費の記録(processed_message。Framework 6.4・13.2。ADR-0028)。業務の更新と同じトランザクションで、
// 受信したメッセージの ID を記録し、2 回目以降の受信を重複として捨てる。Outbox(発行側)の対になる部品。
// Kafka には依存しない(メッセージの ID とトピック名だけを受け取る)。
dependencies {
    api(project(":shared:kernel"))
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.mockk)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
}
