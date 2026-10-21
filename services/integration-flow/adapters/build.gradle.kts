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
}

// テストで、リソースの Flow が contracts/flows のファイルと同じであることを確かめる
tasks.named<Test>("test") {
    systemProperty(
        "eia.contractsFlows",
        flowsDir.asFile.absolutePath,
    )
}
