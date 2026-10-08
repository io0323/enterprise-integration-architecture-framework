plugins {
    id("eia.jvm-service")
}

application {
    // レガシーの受注の CDC の Anti-Corruption Layer(ADR-0026)。生の CDC を読み、変換して整形済みのトピックに送る
    mainClass.set("io.eia.legacyorderacl.app.MainKt")
}

dependencies {
    implementation(project(":services:legacy-order-acl:adapters"))
    implementation(project(":platform:observability"))
    implementation(project(":platform:schema-registry"))
    // 照合のレガシーの DB のパスワード(SecretProvider。ADR-0027)
    implementation(project(":platform:security"))
    implementation(libs.postgresql)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.netty)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
    integrationTestImplementation(libs.ktor.client.cio)
}

// 統合テストは、ローカル基盤と同じトピックの定義(infra/local/kafka/topics.conf)でトピックを作る。変更でテストをやり直すよう、入力に宣言する
tasks.named<Test>("integrationTest") {
    inputs
        .file(rootProject.layout.projectDirectory.file("infra/local/kafka/topics.conf"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("topics")
    systemProperty(
        "eia.repositoryRoot",
        rootProject.layout.projectDirectory.asFile.absolutePath,
    )
}
