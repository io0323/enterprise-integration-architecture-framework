plugins {
    id("eia.jvm-library")
    // REST の DTO(契約の PlaceOrderRequest / Order)
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":services:order:application"))
    // 監査のテーブルのマイグレーションと、注文の監査の記録(ADR-0017)
    implementation(project(":platform:audit"))
    // 監査の記録に、今の処理の Correlation ID と traceparent を入れる(CurrentTrace。ADR-0017 §8 の A17-6)
    implementation(project(":platform:observability"))
    // 注文のイベントを Outbox で発行する(ADR-0007)。イベントは Canonical Model を経由して作る(ADR-0010 Decision 7)
    implementation(project(":platform:outbox"))
    // 注文 Saga の返信の受信(EventConsumer)と冪等消費の記録(processed_message。ADR-0028・ADR-0029)
    api(project(":platform:messaging-kafka"))
    implementation(project(":platform:inbox"))
    implementation(project(":shared:canonical-model"))
    // 冪等の保存先の Port(IdempotencyStore)を PostgreSQL で実装する(ADR-0022 §3)
    api(project(":platform:api"))
    // REST の認証(eiaJwt)と認可(requireScopes)。JWT の検証は platform/security だけで行う(ADR-0019)
    api(project(":platform:security"))
    // 永続化: Exposed はトランザクションの管理に使い、ロックの意味が重要な SQL は PreparedStatement で書く(MODULE_DESIGN §3)
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    implementation(libs.kotlinx.coroutines.core)
    // 保存する応答のヘッダ(jsonb)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.mockk)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.opentelemetry.sdk.testing)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
    // REST の統合テスト: 実際の JWT(テスト用の鍵で署名し、JWKS をローカルの HTTP で配る)と、応答の契約のスキーマでの検証
    integrationTestImplementation(libs.ktor.server.test.host)
    integrationTestImplementation(libs.nimbus.jose.jwt)
    integrationTestImplementation(libs.json.schema.validator)
    integrationTestImplementation(libs.jackson3.dataformat.yaml)
}

// 書き込むイベントの契約のスキーマ(contracts/avro)をリソースに含める。契約が唯一の真実で、コードに複製しない(ADR-0025 §2)
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.dir("contracts/avro/sales")) {
        include("OrderCreated.avsc", "OrderCancelled.avsc")
        into("contracts/avro/sales")
    }
    // 注文 Saga のコマンド(Orchestrator が書く。ADR-0029 §2)
    from(rootProject.layout.projectDirectory.dir("contracts/avro")) {
        include(
            "inventory/ReserveStock.avsc",
            "inventory/ReleaseStock.avsc",
            "payment/AuthorizePayment.avsc",
            "payment/VoidPayment.avsc",
            "shipping/ArrangeShipment.avsc",
            "shipping/CancelShipment.avsc",
        )
        into("contracts/avro")
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
