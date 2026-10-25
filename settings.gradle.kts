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
    ":shared:integration-sdk:http",
    ":shared:integration-sdk:mqtt",
    ":platform:api",
    ":platform:audit",
    ":platform:batch",
    ":platform:file-transfer",
    ":platform:inbox",
    ":platform:messaging-kafka",
    ":platform:observability",
    ":platform:outbox",
    ":platform:reliability",
    ":platform:schema-registry",
    ":platform:security",
    ":platform:test-support",
    ":platform:webhook",
    ":services:order:domain",
    ":services:order:application",
    ":services:order:adapters",
    ":services:order:app",
    ":services:legacy-sim:app",
    ":services:saas-mock:app",
    ":services:webhook-receiver:domain",
    ":services:webhook-receiver:application",
    ":services:webhook-receiver:adapters",
    ":services:webhook-receiver:app",
    ":services:integration-flow:domain",
    ":services:integration-flow:application",
    ":services:integration-flow:adapters",
    ":services:integration-flow:app",
    ":services:legacy-order-acl:domain",
    ":services:legacy-order-acl:application",
    ":services:legacy-order-acl:adapters",
    ":services:legacy-order-acl:app",
    ":services:inventory:domain",
    ":services:inventory:application",
    ":services:inventory:adapters",
    ":services:inventory:app",
    ":services:payment:domain",
    ":services:payment:application",
    ":services:payment:adapters",
    ":services:payment:app",
    ":services:shipping:domain",
    ":services:shipping:application",
    ":services:shipping:adapters",
    ":services:shipping:app",
    ":services:batch-etl:domain",
    ":services:batch-etl:application",
    ":services:batch-etl:adapters",
    ":services:batch-etl:app",
    ":services:file-exchange:domain",
    ":services:file-exchange:application",
    ":services:file-exchange:adapters",
    ":services:file-exchange:app",
    ":tools:architecture-test",
    ":tools:audit-verify",
    ":tools:contract-check",
    ":tools:device-simulator",
    ":tools:dlq-replay",
    ":tools:schema-publish",
    ":tests:e2e",
)
