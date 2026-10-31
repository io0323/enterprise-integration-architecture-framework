plugins {
    id("eia.jvm-library")
}

// 取引先の台帳(TPA。Framework 11.1。ADR-0040): 台帳の JSON の読み込みと検証(JSON Schema と、取引先の重複・文書ごとの設定)、
// 鍵とホスト鍵の有効期限のメトリクス。台帳を読むのは b2b-gateway と、送信を移すまでの file-exchange(P12 ⑦)。
dependencies {
    api(project(":shared:kernel"))
    // 台帳には秘密情報の名前だけを書く(値は SecretProvider から読む。ADR-0019 §6)
    api(project(":platform:security"))
    // 台帳の形の検証(JSON Schema。Jackson 3 の JsonNode を使う)
    implementation(libs.json.schema.validator)
    implementation(libs.opentelemetry.api)

    testImplementation(libs.opentelemetry.sdk.testing)
    // カタログ(contracts/catalog の YAML)との照合
    testImplementation(libs.jackson3.dataformat.yaml)
}

// テストで、ローカル基盤の台帳(infra/local/b2b/partners.json)を読み、カタログ(contracts/catalog)と照らし合わせる
tasks.named<Test>("test") {
    systemProperty(
        "eia.partnersFile",
        rootProject.layout.projectDirectory
            .file("infra/local/b2b/partners.json")
            .asFile.absolutePath,
    )
    systemProperty(
        "eia.catalogDir",
        rootProject.layout.projectDirectory
            .dir("contracts/catalog")
            .asFile.absolutePath,
    )
}
