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
