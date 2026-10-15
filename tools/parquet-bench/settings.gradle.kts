// Parquet の書き出しの手段の計測(P09 ②。ADR-0033、docs/reports/p09-parquet-writer.md)。
// 本体のビルド(ルートの settings.gradle.kts)には含めない独立したビルド。手動で `../../gradlew -p tools/parquet-bench ...` で動かす。
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "parquet-bench"

include("common", "parquet-java", "parquet-java-hadoop", "duckdb", "arrow", "verify")
