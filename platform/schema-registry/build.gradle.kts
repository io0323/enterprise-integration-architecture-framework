plugins {
    id("eia.jvm-library")
    // Apicurio の REST API の応答(JSON)
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Schema Registry(Apicurio Registry 3)のクライアント: 内容からのスキーマ ID の解決・ID からのスキーマの取得・契約の登録(ADR-0025)。
// サービスが書き込みに使うスキーマの ID は起動時に [SchemaIdBook] で解決し、リクエストの処理中にはレジストリへ問い合わせない。
// Apicurio の公式の SDK(kiota / Vert.x を引き込む)は使わず、Ktor Client で REST API を直接呼ぶ。
dependencies {
    api(project(":shared:kernel"))
    api(libs.ktor.client.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.ktor.client.mock)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.ktor.client.cio)
}
