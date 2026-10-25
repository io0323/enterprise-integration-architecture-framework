import io.eia.buildlogic.configureKmp

// native の実行ファイルの tools/* 用(tools/device-simulator。ADR-0004・ADR-0038)。実行ファイルは linuxX64・macosArm64。
// jvm はテストとカバレッジ(Kover は JVM だけ)のために持つ。実行ファイルの設定(entryPoint)はモジュールの build.gradle.kts に書く。
configureKmp {
    jvm()
    native()
}
