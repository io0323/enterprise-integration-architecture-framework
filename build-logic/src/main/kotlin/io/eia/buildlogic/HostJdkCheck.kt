package io.eia.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * Apple Silicon の Mac で、Gradle を x86_64 の JDK(Rosetta)で起動していたらビルドを失敗させる。
 *
 * x86_64 の JVM では Kotlin/Native がホストを macosX64 と判定し、macosArm64 のテストを黙ってスキップする。
 * そのため `./gradlew build` が成功しても、ADR-0004 の macosArm64 の検証が行われていないことに気づけない。
 */
internal fun Project.requireArm64JdkOnAppleSilicon() {
    val osName = providers.systemProperty("os.name").getOrElse("")
    val osArch = providers.systemProperty("os.arch").getOrElse("")
    if (!osName.startsWith("Mac") || osArch == "aarch64") return

    // Rosetta 上のプロセスでも hw.optional.arm64 はハードウェアの値(Apple Silicon なら 1)を返す
    val appleSilicon =
        providers
            .exec {
                commandLine("sysctl", "-n", "hw.optional.arm64")
                isIgnoreExitValue = true
            }.standardOutput.asText
            .get()
            .trim() == "1"
    if (!appleSilicon) return

    val javaHome = providers.systemProperty("java.home").getOrElse("(不明)")
    throw GradleException(
        """
        |Apple Silicon の Mac で、x86_64 の JDK(Rosetta)を使って Gradle を起動しています(os.arch=$osArch)。
        |このままでは Kotlin/Native がホストを macosX64 と判定し、macosArm64 のテストがスキップされます。
        |  使用中の JDK: $javaHome
        |対処方法:
        |  1. arm64 版の JDK 21 を用意する(例: Temurin 21 の aarch64 版、/opt/homebrew の Homebrew で入れた JDK、
        |     または foojay が取得済みの ~/.gradle/jdks/*-21-aarch64-*)。
        |  2. JAVA_HOME をその JDK に設定する(IDE では Gradle JVM の設定も変更する)。
        |  3. `./gradlew --stop` で x86_64 の Gradle デーモンを止めてから、ビルドをやり直す。
        |確認方法は README の「開発環境の前提」を参照。
        """.trimMargin(),
    )
}
