plugins {
    id("eia.jvm-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":services:bff-graphql:application"))
    // 検証したトークン(スコープとクライアント)
    implementation(project(":platform:security"))
    api(libs.graphql.java)
    api(libs.ktor.server.core)
    api(libs.opentelemetry.api)
    implementation(project(":shared:resilience"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.opentelemetry.sdk.testing)
}

// 契約の SDL をリソースに含める。契約が唯一の真実で、コードに複製しない(ADR-0043 §2)
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.dir("contracts/graphql")) {
        include("storefront.v1.graphqls")
        into("contracts/graphql")
    }
}

// テストで、リソースの SDL が契約のファイルと同じであることを確かめる
tasks.named<Test>("test") {
    inputs
        .dir(rootProject.layout.projectDirectory.dir("contracts/graphql"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("contracts")
    systemProperty("eia.rootDir", rootDir.absolutePath)
}
