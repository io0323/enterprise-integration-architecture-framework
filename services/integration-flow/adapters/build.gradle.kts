plugins {
    id("eia.jvm-library")
    // 元のレコード(SaaS の JSON)の読み取りと、Canonical Model の JSON
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":services:integration-flow:application"))
    // 写した結果を Canonical Model に変える(domain と application は Canonical に依存しない。ADR-0010 Decision 7)
    api(project(":shared:canonical-model"))
    // Flow の定義(YAML)の読み取りと、起動時の JSON Schema の検証(ADR-0036 §1)
    implementation(libs.json.schema.validator)
    implementation(libs.jackson3.dataformat.yaml)
    implementation(libs.kotlinx.serialization.json)
    // 取り込みのトピックの購読(EventConsumer。ADR-0028)・冪等消費の記録(platform/inbox)・Canonical のイベントの発行(Outbox。ADR-0007)
    api(project(":platform:messaging-kafka"))
    implementation(project(":platform:inbox"))
    implementation(project(":platform:outbox"))
    implementation(project(":platform:observability"))
    // SaaS の API の Connector: 429 と Retry-After の分類(HttpCallClassifier)・Resilience(ADR-0021・ADR-0035 §6)・API トークン(SecretProvider)
    api(project(":platform:reliability"))
    api(project(":platform:security"))
    api(libs.ktor.client.core)
    // 永続化: Exposed はトランザクションの管理に使い、ロックの意味が重要な SQL は PreparedStatement で書く(MODULE_DESIGN §3)
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.opentelemetry.api)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.opentelemetry.sdk.testing)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
    integrationTestImplementation(libs.ktor.client.cio)
    integrationTestImplementation(libs.opentelemetry.sdk.testing)
}

// Flow の定義と JSON Schema(contracts/flows)をリソースに含める。契約が唯一の真実で、コードに複製しない。
// クラスパスのディレクトリは一覧できないので、ファイルの一覧(index.txt)も作る
val flowsDir: Directory = rootProject.layout.projectDirectory.dir("contracts/flows")
val flowIndex =
    tasks.register("flowIndex") {
        val input = flowsDir
        val output = layout.buildDirectory.dir("generated/flow-index")
        inputs.dir(input).withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir(output)
        doLast {
            val names =
                input.asFile
                    .listFiles()
                    .orEmpty()
                    .map { it.name }
                    .filter { it.endsWith(".flow.yaml") }
                    .sorted()
            val target = output.get().file("contracts/flows/index.txt").asFile
            target.parentFile.mkdirs()
            target.writeText(names.joinToString("\n", postfix = "\n"))
        }
    }
sourceSets.main {
    resources.srcDir(flowIndex)
}
tasks.named<ProcessResources>("processResources") {
    from(flowsDir) {
        include("*.flow.yaml", "flow.v1.schema.json")
        into("contracts/flows")
    }
    // 読むイベント(取り込みのトピック)と書くイベント(Canonical の顧客)の契約のスキーマ(ADR-0025 §2)
    from(rootProject.layout.projectDirectory.dir("contracts/avro")) {
        include("integration/WebhookReceived.avsc", "sales/CustomerChanged.avsc")
        into("contracts/avro")
    }
}

// テストで、リソースの Flow が contracts/flows のファイルと同じであることを確かめる
tasks.named<Test>("test") {
    systemProperty(
        "eia.contractsFlows",
        flowsDir.asFile.absolutePath,
    )
    systemProperty(
        "eia.contractsAvro",
        rootProject.layout.projectDirectory
            .dir("contracts/avro")
            .asFile.absolutePath,
    )
}
