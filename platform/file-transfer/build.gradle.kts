plugins {
    id("eia.jvm-library")
    // マニフェスト(.manifest.json)と隔離の理由(.reason.json)の JSON
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ファイル連携の部品(Framework 9。ADR-0032・ADR-0033): S3 互換ストレージの読み書き、マニフェスト、データファイルの書き出し(Parquet・CSV + gzip)、
// 受け側の検査(SHA-256・件数・スキーマの版)、archive / quarantine への移動、PGP の暗号化と署名、SFTP の送信。AWS SDK を使ってよいのは、このモジュールと audit のアンカーだけ(Konsist)。
dependencies {
    api(project(":shared:kernel"))
    // S3 の資格情報は SecretProvider から取る(ADR-0019 §6)
    api(project(":platform:security"))
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3) {
        // HTTP の実装は url-connection-client だけにする(ADR-0017)
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation(libs.aws.sdk.url.connection.client)
    implementation(libs.parquet.hadoop) {
        // zstd だけを使う(ZstdCodecFactory)。既定の CodecFactory の部品(snappy-java はネイティブ)は使わない(ADR-0033 §1)
        exclude(group = "org.xerial.snappy", module = "snappy-java")
        exclude(group = "io.airlift", module = "aircompressor")
        exclude(group = "commons-pool", module = "commons-pool")
    }
    // SFTP(ADR-0032 §8)。sshj の Bouncy Castle(1.84)を、bcpg と同じ版(libs の bouncycastle)にそろえる
    implementation(libs.sshj)
    implementation(libs.bouncycastle.pkix)
    // PGP(暗号化と署名。ADR-0032 §8)
    implementation(libs.bouncycastle.pg)
    // ParquetWriter.Builder・WriteSupport の抽象メソッドの引数の型のためだけ。実行時には入れない(ADR-0033 §1)
    compileOnly(libs.hadoop.client.api) { isTransitive = false }

    // テスト用の鍵(TestKeys)と SFTP のサーバ(SftpServerContainer)
    testImplementation(project(":platform:test-support"))
    integrationTestImplementation(project(":platform:test-support"))
    // バケットを作る(管理の identity)
    integrationTestImplementation(platform(libs.aws.sdk.bom))
    integrationTestImplementation(libs.aws.sdk.s3)
    integrationTestImplementation(libs.aws.sdk.url.connection.client)
    // 書いた Parquet を別の実装で読み直す(ADR-0033 §4)
    integrationTestImplementation(libs.duckdb.jdbc)
}

// ADR-0033 §3: Hadoop を実行時のクラスパスに入れない。compileOnly はテストの実行時のクラスパスにも入らないので、
// 単体テスト(Parquet の書き出しとフッタの読み取り)が Hadoop なしで動くことの証拠になる。ここでは宣言の側を検査する。
val runtimeComponents =
    listOf("runtimeClasspath", "testRuntimeClasspath", "integrationTestRuntimeClasspath").associateWith {
        configurations.named(it).flatMap { configuration -> configuration.incoming.resolutionResult.rootComponent }
    }
val verifyNoHadoopAtRuntime =
    tasks.register("verifyNoHadoopAtRuntime") {
        description = "実行時のクラスパスに org.apache.hadoop のモジュールが含まれていないことを検査する(ADR-0033 §3)。"
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        val roots = runtimeComponents
        doLast {
            val found =
                roots.flatMap { (name, root) ->
                    val seen = mutableSetOf<org.gradle.api.artifacts.result.ResolvedComponentResult>()
                    val queue = ArrayDeque(listOf(root.get()))
                    buildList {
                        while (queue.isNotEmpty()) {
                            val component = queue.removeFirst()
                            if (!seen.add(component)) continue
                            val id = component.moduleVersion
                            if (id?.group == "org.apache.hadoop") add("$name: ${id.group}:${id.name}:${id.version}")
                            component.dependencies
                                .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                                .forEach { queue += it.selected }
                        }
                    }
                }
            check(found.isEmpty()) { "実行時のクラスパスに Hadoop があります(ADR-0033 §3):\n${found.joinToString("\n")}" }
        }
    }
tasks.named("check") { dependsOn(verifyNoHadoopAtRuntime) }
