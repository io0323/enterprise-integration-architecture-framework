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
                // アラートと Runbook・カタログの SLO の規則(ADR-0045)
                include("infra/local/prometheus/rules/*.rules.yml")
                include("docs/runbooks/*.md")
                include("contracts/catalog/*.yaml")
                // NFR のレポートの連携の一覧(ADR-0048)
                include("docs/reports/p14-nfr.md")
                include("docs/reports/p14-nfr-integrations.json")
                exclude("**/build/**", ".gradle/**", "**/.kotlin/**", "**/node_modules/**")
            },
        ).withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("repositorySources")
    systemProperty("eia.rootDir", repositoryRoot.absolutePath)
}
