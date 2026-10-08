plugins {
    id("eia.root")
}

// 全体カバレッジの集約対象(JVM で計測できるモジュール)。モジュール追加時に追記する。
dependencies {
    kover(project(":shared:kernel"))
    kover(project(":shared:canonical-model"))
    kover(project(":services:order:domain"))
    kover(project(":services:order:application"))
    kover(project(":services:order:adapters"))
    kover(project(":services:order:app"))
    kover(project(":services:legacy-sim:app"))
}
