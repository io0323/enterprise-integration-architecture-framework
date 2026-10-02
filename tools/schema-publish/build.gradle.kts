plugins {
    id("eia.jvm-service")
}

// 契約(contracts/asyncapi のチャネルと contracts/avro のスキーマ)を Schema Registry に登録する(`make schemas`。ADR-0025 §2)。
// サービスは自動登録しないため、イベントを書く前にこのツールで登録しておく。互換性の違反はレジストリ(FULL_TRANSITIVE)が拒否する。
application {
    mainClass.set("io.eia.tools.schemapublish.MainKt")
}

dependencies {
    implementation(project(":platform:schema-registry"))
    implementation(libs.jackson3.dataformat.yaml)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.ktor.client.mock)

    integrationTestImplementation(project(":platform:test-support"))
}

// テストはリポジトリの契約(contracts/)を読む。契約の変更でテストをやり直すよう、入力に宣言する
tasks.withType<Test>().configureEach {
    val contracts = rootDir.resolve("contracts")
    inputs.dir(contracts).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("contracts")
    systemProperty("eia.contractsDir", contracts.absolutePath)
}
