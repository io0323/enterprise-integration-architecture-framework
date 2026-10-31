plugins {
    id("eia.jvm-library")
}

dependencies {
    api(project(":services:b2b-gateway:application"))
    // EDI のサブセットの仕様(contracts/files/edi の YAML)を読む。仕様が単一の真実で、コードに複製しない(ADR-0041 §7)
    implementation(libs.jackson3.dataformat.yaml)
    // 取引先の台帳(file-exchange と共有する 1 つの台帳。ADR-0040)
    api(project(":platform:trading-partner"))

    // 原本の保管(Object Lock)と、B2B の文書の PGP(ADR-0040 §5・§8)
    api(project(":platform:file-transfer"))
    // 受け取りの記録と同じトランザクションで監査を記録する(ADR-0017)
    api(project(":platform:audit"))
    implementation(project(":platform:observability"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(project(":platform:test-support"))

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
    // バケットを作る・版と保持の設定を確かめる(管理の identity)
    integrationTestImplementation(platform(libs.aws.sdk.bom))
    integrationTestImplementation(libs.aws.sdk.s3)
    integrationTestImplementation(libs.aws.sdk.url.connection.client)
}

// EDI のサブセットの仕様をリソースに含める(版を足すときは、契約にファイルを足すだけでリソースに入る)
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.dir("contracts/files/edi")) {
        include("*.yaml")
        into("contracts/files/edi")
    }
}

// テストで、リソースの仕様が契約のファイルと同じであることを確かめる
tasks.named<Test>("test") {
    systemProperty(
        "eia.contractsEdi",
        rootProject.layout.projectDirectory
            .dir("contracts/files/edi")
            .asFile.absolutePath,
    )
}
