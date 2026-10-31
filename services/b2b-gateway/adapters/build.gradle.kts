plugins {
    id("eia.jvm-library")
}

dependencies {
    api(project(":services:b2b-gateway:application"))
    // EDI のサブセットの仕様(contracts/files/edi の YAML)を読む。仕様が単一の真実で、コードに複製しない(ADR-0041 §7)
    implementation(libs.jackson3.dataformat.yaml)
    // 取引先の台帳(file-exchange と共有する 1 つの台帳。ADR-0040)
    api(project(":platform:trading-partner"))

    testImplementation(project(":platform:test-support"))
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
