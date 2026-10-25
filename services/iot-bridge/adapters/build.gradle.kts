plugins {
    id("eia.jvm-library")
}

dependencies {
    api(project(":services:iot-bridge:application"))
    // デバイスのペイロードを形式のスキーマ(contracts/devices の JSON Schema)で検証する。契約が唯一の真実で、コードに複製しない(ADR-0039 §4)
    implementation(libs.json.schema.validator)
}

// デバイスの形式のスキーマをリソースに含める(版を足すときは、契約にファイルを足すだけでリソースに入る)
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.dir("contracts/devices")) {
        include("*.schema.json")
        into("contracts/devices")
    }
}

// テストで、リソースのスキーマが契約のファイルと同じであることを確かめる
tasks.named<Test>("test") {
    systemProperty(
        "eia.contractsDevices",
        rootProject.layout.projectDirectory
            .dir("contracts/devices")
            .asFile.absolutePath,
    )
}
