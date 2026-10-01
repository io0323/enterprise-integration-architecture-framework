plugins {
    id("eia.jvm-library")
}

// E2E(ROADMAP P05。MODULE_DESIGN)。起動したローカル基盤(`make up PROFILE=order`)に、公開されたエンドポイント
// (Gateway・Keycloak・Tempo・Prometheus・Grafana)だけで接続して確かめる。サービスのコードには依存しない。
// `make e2e`(`./gradlew :tests:e2e:e2eTest`)で実行し、`build` / `check` には含めない。CI は ci.yml の `e2e` ジョブ。
val e2eTest: SourceSet = sourceSets.create("e2eTest")
configurations.named(e2eTest.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named(e2eTest.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    "e2eTestImplementation"(libs.kotlinx.serialization.json)
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
    // 結果は基盤の状態で決まるので、入力が同じでも毎回実行する
    outputs.upToDateWhen { false }
    // 開発用の証明書(mTLS の確認)はリポジトリのルートからの相対パスで読む
    systemProperty("eiaf.repo.root", rootProject.layout.projectDirectory.asFile.absolutePath)
    // 秘密情報(EIAF_E2E_CLIENT_SECRET など)は make e2e が環境変数で渡し、テストのプロセスはそれを引き継ぐ。
    // ここで System.getenv を読んで environment(...) に渡すと、値が configuration cache のファイルに書き込まれるので、しない
    testLogging { events("passed", "failed", "skipped") }
}
