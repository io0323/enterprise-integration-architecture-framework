import io.eia.buildlogic.InfraImagesArgument
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("eia.kmp-socket-library")
}

// KMP の連携クライアント SDK の MQTT の部分(ADR-0038・ADR-0039 §1)。デバイスの側の MQTT v5 の最小限のクライアント(ktor-network の TCP)。
// HTTP の部分(shared/integration-sdk/http)には依存しない(device-simulator が libcurl などを巻き込まないように)。
// 再接続の待ち時間・Retry は shared/resilience と kernel の RetryPolicy、traceparent は shared/resilience を使う。
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:resilience"))
            implementation(libs.ktor.network)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// 実際の Mosquitto(images.env の MOSQUITTO_IMAGE)に対する統合テスト(jvm だけ。src/integrationTest/kotlin)。
// JVM のモジュールの規約(eia.jvm-library)と同じく、check には含めず `./gradlew integrationTest` で実行する。
val integrationTestCompilation =
    extensions.getByType<KotlinMultiplatformExtension>().jvm().compilations.create("integrationTest") {
        associateWith(this@create.target.compilations.getByName("main"))
        defaultSourceSet {
            kotlin.srcDir("src/integrationTest/kotlin")
            dependencies {
                implementation(project(":platform:test-support"))
                implementation(libs.kotest.runner.junit5)
                implementation(libs.kotest.assertions.core)
            }
        }
    }

tasks.register<Test>("integrationTest") {
    description = "Testcontainers の Mosquitto を使う統合テストを実行する。"
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = integrationTestCompilation.output.classesDirs
    classpath = integrationTestCompilation.output.allOutputs + integrationTestCompilation.runtimeDependencyFiles
    useJUnitPlatform()
    jvmArgumentProviders.add(
        objects.newInstance<InfraImagesArgument>().apply {
            imagesFile.set(
                rootProject.layout.projectDirectory.file("infra/local/images.env"),
            )
        },
    )
    // 実際のミドルウェアの振る舞いに依存するので、毎回実行する(eia.jvm-library と同じ。#105)
    outputs.cacheIf("統合テストは宣言していない外部の状態に依存する") { false }
    outputs.upToDateWhen { false }
}
