plugins {
    id("eia.jvm-library")
}

// E2E(ROADMAP P05。MODULE_DESIGN)。起動したローカル基盤(`make up PROFILE="order cdc saga batch file saas iot" E2E=1`)に、公開されたエンドポイント
// (Gateway・Keycloak・Tempo・Prometheus・Grafana・Kafka・Schema Registry)だけで接続して確かめる。サービスのコードには依存しない。
// `make e2e`(`./gradlew :tests:e2e:e2eTest`)で実行し、`build` / `check` には含めない。CI は ci.yml の `e2e` ジョブ。
val e2eTest: SourceSet = sourceSets.create("e2eTest")
configurations.named(e2eTest.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named(e2eTest.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    // client-credentials のトークンの取得(期限の前に取り直す)は SDK を使う(同じ処理を 2 か所に持たない。#14・ADR-0037 §5)
    "e2eTestImplementation"(project(":shared:integration-sdk:http"))
    "e2eTestImplementation"(libs.kotlinx.serialization.json)
    // 発行されたイベントを Kafka から読み、契約のスキーマ(Schema Registry から取る)で解釈する(P06。サービスのコードには依存しない)
    "e2eTestImplementation"(libs.kafka.clients)
    "e2eTestImplementation"(libs.avro)
    // デバイスとしての MQTT の送信(IotE2E。他のデバイスのトピックの拒否と再送)。シミュレータと同じ SDK の MQTT の部分(P11)
    "e2eTestImplementation"(project(":shared:integration-sdk:mqtt"))
}

// デバイスのシミュレータ(IotE2E。P11 ⑦)。この OS の native の release の実行ファイルを組み立てて使う
// (CI の ubuntu は linuxX64、手元の Mac は macosArm64。DoD は native のバイナリから送ること)。それ以外の OS では IotE2E が失敗する
val simulatorTarget: String? =
    when {
        System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64") -> "LinuxX64"
        System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") == "aarch64" -> "MacosArm64"
        else -> null
    }

// 本番のコードがないモジュールなので、Kover を無効にする(行が 0 のモジュールの閾値の検証が失敗するため)。
// 加えて、e2eTest を計測から外す。Kover は既定で全 Test タスクを成果物の生成の依存に加えるので、disable() だけでは
// build が起動した基盤の要る e2eTest まで実行してしまう
kover {
    disable()
    currentProject {
        instrumentation {
            disabledForTestTasks.add("e2eTest")
        }
    }
}

tasks.register<Test>("e2eTest") {
    description = "起動したローカル基盤に対して E2E のシナリオを実行する(make e2e)。"
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = e2eTest.output.classesDirs
    classpath = e2eTest.runtimeClasspath
    // 結果は基盤の状態で決まるので、入力が同じでも毎回実行する(ビルドキャッシュからも取らない)
    outputs.upToDateWhen { false }
    outputs.cacheIf("E2E は起動した基盤の状態に依存する") { false }
    // 開発用の証明書(mTLS の確認)はリポジトリのルートからの相対パスで読む
    systemProperty("eiaf.repo.root", rootProject.layout.projectDirectory.asFile.absolutePath)
    // DLQ の Replay の CLI(SagaE2E。make dlq-replay と同じ tools/dlq-replay)。サービスのコードではなく運用のツールとして呼ぶ
    dependsOn(":tools:dlq-replay:installDist")
    systemProperty(
        "eiaf.dlqReplay",
        rootProject.layout.projectDirectory
            .file("tools/dlq-replay/build/install/dlq-replay/bin/dlq-replay")
            .asFile.absolutePath,
    )
    simulatorTarget?.let { target ->
        dependsOn(":tools:device-simulator:linkReleaseExecutable$target")
        val directory = target.replaceFirstChar(Char::lowercase)
        systemProperty(
            "eiaf.deviceSimulator",
            rootProject.layout.projectDirectory
                .file("tools/device-simulator/build/bin/$directory/releaseExecutable/device-simulator.kexe")
                .asFile.absolutePath,
        )
    }
    // 秘密情報(EIAF_E2E_CLIENT_SECRET など)は make e2e が環境変数で渡し、テストのプロセスはそれを引き継ぐ。
    // ここで System.getenv を読んで environment(...) に渡すと、値が configuration cache のファイルに書き込まれるので、しない
    testLogging { events("passed", "failed", "skipped") }
}
