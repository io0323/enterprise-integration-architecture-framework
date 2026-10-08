pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // settings プラグインはバージョンカタログを参照できないため、ここで固定する(最新安定版: 2026-09-25 確認)。
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "enterprise-integration-architecture-framework"

include(
    ":shared:kernel",
    ":shared:resilience",
    ":shared:canonical-model",
    ":platform:api",
    ":platform:audit",
    ":platform:messaging-kafka",
    ":platform:observability",
    ":platform:outbox",
    ":platform:reliability",
    ":platform:schema-registry",
    ":platform:security",
    ":platform:test-support",
    ":services:order:domain",
    ":services:order:application",
    ":services:order:adapters",
    ":services:order:app",
    ":services:legacy-sim:app",
    ":services:legacy-order-acl:domain",
    ":services:legacy-order-acl:application",
    ":services:legacy-order-acl:adapters",
    ":services:legacy-order-acl:app",
    ":tools:architecture-test",
    ":tools:audit-verify",
    ":tools:contract-check",
    ":tools:schema-publish",
    ":tests:e2e",
)
