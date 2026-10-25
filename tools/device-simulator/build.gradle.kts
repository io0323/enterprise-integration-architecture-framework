import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("eia.kmp-native-tool")
}

// IoT のデバイスのシミュレータ(P11 ④。ADR-0039)。native の実行ファイル(linuxX64・macosArm64)。
// 依存してよいのは SDK の MQTT の部分だけ(HTTP の部分の libcurl などを巻き込まない。MODULE_DESIGN §2。Konsist で検査)。
extensions.configure<KotlinMultiplatformExtension> {
    listOf(linuxX64(), macosArm64()).forEach { target ->
        target.binaries.executable {
            baseName = "device-simulator"
            entryPoint = "io.eia.tools.devicesimulator.main"
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:integration-sdk:mqtt"))
        }
        // 作ったペイロードが契約(contracts/devices の JSON Schema)に適合することを確かめる(JVM のテストだけ)
        jvmTest.dependencies {
            implementation(libs.json.schema.validator)
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty(
        "eia.contractsDir",
        rootProject.layout.projectDirectory
            .dir("contracts")
            .asFile.absolutePath,
    )
}
