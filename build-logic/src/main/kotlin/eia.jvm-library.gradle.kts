import io.eia.buildlogic.InfraImagesArgument
import io.eia.buildlogic.JVM_TOOLCHAIN
import io.eia.buildlogic.TEST_SUPPORT_PROJECT
import io.eia.buildlogic.findProjectPath
import io.eia.buildlogic.library
import io.eia.buildlogic.libs
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// platform/*, services/*/adapters, tools/* 用の JVM モジュール。
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("eia.quality")
}

kotlin {
    jvmToolchain(JVM_TOOLCHAIN)
    // CODING_STANDARDS: shared/* と platform/* は明示 API モード
    if (path.startsWith(":platform:")) explicitApi()
}

dependencies {
    testImplementation(libs.library("kotest-runner-junit5"))
    testImplementation(libs.library("kotest-assertions-core"))
}

// integrationTest ソースセット規約: src/integrationTest/kotlin。Testcontainers を使うテストはここに置く。
// `check` には含めず、`./gradlew integrationTest` で明示的に実行する。
val integrationTest: SourceSet = sourceSets.create("integrationTest")
configurations.named(integrationTest.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named(integrationTest.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}
extensions.getByType<KotlinJvmProjectExtension>().target.compilations.run {
    getByName("integrationTest").associateWith(getByName("main"))
}

// integrationTest は main と関連付けたコンパイルのため、Kover が既定でテストクラス自体を計測対象に含めてしまう。
// カバレッジの母数はプロダクションコード(main)だけにする。
extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
    currentProject {
        sources {
            excludedSourceSets.add(integrationTest.name)
        }
    }
}

// ADR-0016 §5: platform/test-support(Testcontainers を含む)を main のクラスパスに載せない。
// Konsist はソースの参照しか見られないため、Gradle の依存宣言(推移的な依存を含む)はここで検査する。
if (path != TEST_SUPPORT_PROJECT) {
    val mainClasspaths =
        listOf("compileClasspath", "runtimeClasspath").associateWith {
            configurations.named(it).flatMap { configuration -> configuration.incoming.resolutionResult.rootComponent }
        }
    val projectPath = path
    val verifyNoTestSupportInMain =
        tasks.register("verifyNoTestSupportInMain") {
            description = "main のクラスパスに $TEST_SUPPORT_PROJECT が含まれていないことを検査する(ADR-0016 §5)。"
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            doLast {
                mainClasspaths.forEach { (name, root) ->
                    findProjectPath(root.get(), TEST_SUPPORT_PROJECT)?.let { route ->
                        throw GradleException(
                            "$projectPath の $name に $TEST_SUPPORT_PROJECT が含まれています(${route.joinToString(" -> ")})。" +
                                "testImplementation / integrationTestImplementation で宣言してください(ADR-0016 §5)",
                        )
                    }
                }
            }
        }
    tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(verifyNoTestSupportInMain) }
}

tasks.register<Test>("integrationTest") {
    description = "Testcontainers などの実ミドルウェアを使う統合テストを実行する。"
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
}

// ADR-0016 §5: Testcontainers は compose と同じ infra/local/images.env のイメージを使う(platform/test-support の InfraImages)。
val infraImagesFile = rootProject.layout.projectDirectory.file("infra/local/images.env")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgumentProviders.add(
        objects.newInstance<InfraImagesArgument>().apply { imagesFile.set(infraImagesFile) },
    )
}
