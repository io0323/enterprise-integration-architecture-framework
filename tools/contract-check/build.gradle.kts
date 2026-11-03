import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject

plugins {
    id("eia.jvm-service")
    // テストで一致検査用の @Serializable 型を定義する
    id("org.jetbrains.kotlin.plugin.serialization")
}

application {
    mainClass.set("io.eia.tools.contract.MainKt")
}

dependencies {
    implementation(project(":shared:canonical-model"))
    implementation(libs.avro)
    implementation(libs.swagger.parser)
    // SDL の検査と破壊的変更の検知(ADR-0043)
    implementation(libs.graphql.java)
    implementation(libs.json.schema.validator)
    implementation(libs.jackson3.dataformat.yaml)
    runtimeOnly(libs.slf4j.nop)
}

// ---------------------------------------------------------------------------------------------
// oasdiff(OpenAPI の破壊的変更検知。ADR-0013)。GitHub Releases から取得し、SHA-256 を検証してから使う。
// 版は libs.versions.toml、チェックサムは下の表で固定する。版を上げるときは両方を更新する。
// ---------------------------------------------------------------------------------------------
val oasdiffVersion: String = libs.versions.oasdiff.get()

// https://github.com/oasdiff/oasdiff/releases/download/v1.32.1/checksums.txt(2026-09-26 取得)
val oasdiffChecksums =
    mapOf(
        "darwin_all" to "e4d74b7e2dfb9d4819e7fc720c905ec86547e4637ac270a2b0187c0f1fb7187e",
        "linux_amd64" to "7c8939fc49b75ee11fec66a5b83b37a2fca6aee109fed85013b1ba2ac2a1ee7f",
        "linux_arm64" to "32fff58a120f75a723d6c2422444691c37fa6813fed61d23f53dbcb604b30f6d",
    )

val oasdiffPlatform: String =
    run {
        val os = providers.systemProperty("os.name").get().lowercase()
        val arch = providers.systemProperty("os.arch").get().lowercase()
        when {
            os.contains("mac") -> "darwin_all"
            os.contains("linux") && (arch == "amd64" || arch == "x86_64") -> "linux_amd64"
            os.contains("linux") && (arch == "aarch64" || arch == "arm64") -> "linux_arm64"
            else -> "unsupported"
        }
    }

/** URL からファイルを取得し、SHA-256 が一致したときだけ出力する。 */
abstract class DownloadVerified : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val destination: RegularFileProperty

    @TaskAction
    fun download() {
        val target = destination.get().asFile.toPath()
        Files.createDirectories(target.parent)
        val partial = target.resolveSibling("${target.fileName}.part")
        URI(url.get()).toURL().openStream().use { input -> Files.copy(input, partial, StandardCopyOption.REPLACE_EXISTING) }
        val actual = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(partial)).joinToString("") { "%02x".format(it) }
        if (actual != sha256.get()) {
            Files.delete(partial)
            throw GradleException("チェックサムが一致しません: ${url.get()}(期待値 ${sha256.get()}、実際 $actual)")
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

val downloadOasdiff by tasks.registering(DownloadVerified::class) {
    description = "oasdiff をチェックサムを検証して取得する。"
    val asset = "oasdiff_${oasdiffVersion}_$oasdiffPlatform.tar.gz"
    url.set("https://github.com/oasdiff/oasdiff/releases/download/v$oasdiffVersion/$asset")
    sha256.set(
        provider {
            oasdiffChecksums[oasdiffPlatform]
                ?: throw GradleException("oasdiff の取得に対応していないプラットフォームです: $oasdiffPlatform")
        },
    )
    destination.set(layout.buildDirectory.file("oasdiff/$asset"))
}

val installOasdiff by tasks.registering(Sync::class) {
    description = "取得した oasdiff を展開する。"
    from(tarTree(downloadOasdiff.flatMap { it.destination })) {
        include("oasdiff")
        filePermissions { unix("rwxr-xr-x") }
    }
    into(layout.buildDirectory.dir("oasdiff/$oasdiffVersion"))
}
val oasdiffExecutable: String =
    layout.buildDirectory
        .file("oasdiff/$oasdiffVersion/oasdiff")
        .get()
        .asFile.absolutePath

// ---------------------------------------------------------------------------------------------
// buf(proto の lint と破壊的変更検知。ADR-0042 §3)。oasdiff と同じく、SHA-256 を検証してから使う。
// 版は libs.versions.toml、チェックサムは下の表で固定する。版を上げるときは両方を更新する。
// ---------------------------------------------------------------------------------------------
val bufVersion: String = libs.versions.buf.get()

// https://github.com/bufbuild/buf/releases/download/v1.75.0/sha256.txt(2026-11-02 取得)
val bufChecksums =
    mapOf(
        "Darwin-arm64" to "f71b009ebd8530b12fd85b060c6c245e15d6e5110ccc8da48d8b6fa71e59330b",
        "Darwin-x86_64" to "ff6d0979143c661d59178a032c57be883b2ac03dc9bf7d00ffca222b0354bd46",
        "Linux-x86_64" to "fad1e38d6909bbb583a4da81d8c02a01046e2cd663bde7e337dd24277393764b",
        "Linux-aarch64" to "00eb190aa82df17f393e05b04889cc60a0b13b3259d336e21a87d5f23ab4fd26",
    )

val bufPlatform: String =
    run {
        val os = providers.systemProperty("os.name").get().lowercase()
        val arch = providers.systemProperty("os.arch").get().lowercase()
        val isArm = arch == "aarch64" || arch == "arm64"
        val isX86 = arch == "amd64" || arch == "x86_64"
        when {
            os.contains("mac") && isArm -> "Darwin-arm64"
            os.contains("mac") && isX86 -> "Darwin-x86_64"
            os.contains("linux") && isX86 -> "Linux-x86_64"
            os.contains("linux") && isArm -> "Linux-aarch64"
            else -> "unsupported"
        }
    }

val downloadBuf by tasks.registering(DownloadVerified::class) {
    description = "buf をチェックサムを検証して取得する。"
    val asset = "buf-$bufPlatform"
    url.set("https://github.com/bufbuild/buf/releases/download/v$bufVersion/$asset")
    sha256.set(
        provider {
            bufChecksums[bufPlatform]
                ?: throw GradleException("buf の取得に対応していないプラットフォームです: $bufPlatform")
        },
    )
    destination.set(layout.buildDirectory.file("buf/$asset"))
}

val installBuf by tasks.registering(Sync::class) {
    description = "取得した buf を実行できる形で置く。"
    from(downloadBuf.flatMap { it.destination }) {
        rename { "buf" }
        filePermissions { unix("rwxr-xr-x") }
    }
    into(layout.buildDirectory.dir("buf/$bufVersion"))
}
val bufExecutable: String =
    layout.buildDirectory
        .file("buf/$bufVersion/buf")
        .get()
        .asFile.absolutePath

// ---------------------------------------------------------------------------------------------
// 互換性検査の比較元(baseline)。main の contracts/ を git archive で取り出す(ADR-0013)。
// 比較元のコミットは -Peia.contracts.baseRef で指定する(既定 origin/main)。取り出せなければ baseline なしで実行する。
// ---------------------------------------------------------------------------------------------
abstract class ExtractContractBaseline : DefaultTask() {
    @get:Input
    abstract val baseRef: Property<String>

    @get:Internal
    abstract val repositoryDir: DirectoryProperty

    @get:OutputDirectory
    abstract val baselineDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:Inject
    abstract val archiveOperations: ArchiveOperations

    @TaskAction
    fun extract() {
        val output = baselineDir.get().asFile
        fileSystemOperations.delete { delete(output) }
        output.mkdirs()
        val archive = temporaryDir.resolve("contracts.tar")
        val result =
            execOperations.exec {
                workingDir = repositoryDir.get().asFile
                commandLine("git", "archive", "--format=tar", "-o", archive.absolutePath, baseRef.get(), "contracts")
                isIgnoreExitValue = true
            }
        if (result.exitValue != 0) {
            logger.warn("'${baseRef.get()}' の contracts/ を取り出せませんでした。互換性検査は比較元なし(全て新規)として実行します。")
            output.deleteRecursively()
            return
        }
        fileSystemOperations.copy {
            from(archiveOperations.tarTree(archive))
            into(output)
        }
    }
}

val extractContractBaseline by tasks.registering(ExtractContractBaseline::class) {
    description = "互換性検査の比較元として、baseRef の contracts/ を取り出す。"
    baseRef.set(providers.gradleProperty("eia.contracts.baseRef").orElse("origin/main"))
    repositoryDir.set(rootProject.layout.projectDirectory)
    baselineDir.set(layout.buildDirectory.dir("contracts-baseline"))
    // 比較元のコミットの内容は Gradle の入力として追跡できないため、毎回取り出す
    outputs.upToDateWhen { false }
}

tasks.named<JavaExec>("run") {
    description = "契約(contracts/)を検査する。違反があれば失敗する。"
    dependsOn(installOasdiff, installBuf, extractContractBaseline)
    workingDir = rootDir
    val reportFile = layout.buildDirectory.file("reports/contract-check/summary.md")
    args(
        "--root",
        rootDir.absolutePath,
        "--baseline",
        layout.buildDirectory
            .dir("contracts-baseline")
            .get()
            .asFile.absolutePath,
        "--oasdiff",
        oasdiffExecutable,
        "--buf",
        bufExecutable,
        "--markdown",
        reportFile.get().asFile.absolutePath,
    )
}

tasks.test {
    dependsOn(installOasdiff, installBuf)
    // 実際の contracts/ を検査するテストがあるため、contracts/ を入力として宣言する
    inputs
        .dir(rootProject.layout.projectDirectory.dir("contracts"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("contracts")
    inputs.file(rootProject.layout.projectDirectory.file("tools/contract-check/README.md")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("eia.rootDir", rootDir.absolutePath)
    systemProperty("eia.oasdiff", oasdiffExecutable)
    systemProperty("eia.buf", bufExecutable)
}
