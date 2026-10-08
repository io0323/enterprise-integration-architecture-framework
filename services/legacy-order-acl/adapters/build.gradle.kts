plugins {
    id("eia.jvm-library")
    // 生の CDC の Envelope と、出力のイベントの型(契約の record 名の @SerialName。ADR-0025 §1)
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":services:legacy-order-acl:application"))
    // 生の CDC を読み、整形済みのトピックと DLQ に送る(ADR-0025・ADR-0026)
    api(project(":platform:messaging-kafka"))
    implementation(project(":platform:observability"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.ktor.client.mock)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.ktor.client.cio)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
}

// 出力のイベントの契約のスキーマ(contracts/avro)をリソースに含める。契約が唯一の真実で、コードに複製しない(ADR-0025 §2)
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.file("contracts/avro/sales/LegacyOrderChanged.avsc")) {
        into("contracts/avro/sales")
    }
}

// テストで、リソースのスキーマが契約のファイルと同じであることを確かめる
tasks.named<Test>("test") {
    systemProperty(
        "eia.contractsAvro",
        rootProject.layout.projectDirectory
            .dir("contracts/avro")
            .asFile.absolutePath,
    )
}
