plugins {
    id("eia.jvm-library")
}

dependencies {
    testImplementation(libs.konsist)
}

tasks.test {
    // Konsist はリポジトリ全体のソースを検査する。対象ソースを入力として宣言し、変更時にキャッシュが効かないようにする。
    val repositoryRoot = rootDir
    inputs
        .files(
            fileTree(repositoryRoot) {
                include("**/*.kt")
                // platform 間の依存の規則は platform/*/build.gradle.kts も読む
                include("platform/*/build.gradle.kts")
                exclude("**/build/**", ".gradle/**", "**/.kotlin/**", "**/node_modules/**")
            },
        ).withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("repositorySources")
    systemProperty("eia.rootDir", repositoryRoot.absolutePath)
}
