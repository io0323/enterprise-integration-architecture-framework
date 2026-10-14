plugins {
    // ルートの check / build を持たせる(下の build-logic のテストを ./gradlew build で実行するため)
    base
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
    kover(project(":services:legacy-order-acl:domain"))
    kover(project(":services:legacy-order-acl:application"))
    kover(project(":services:legacy-order-acl:adapters"))
    kover(project(":services:legacy-order-acl:app"))
}

// build-logic(convention plugin)のテスト(#31)も `./gradlew build` で実行する。build-logic は pluginManagement の
// includeBuild なので、ルートの check からは自動で実行されない
tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}
