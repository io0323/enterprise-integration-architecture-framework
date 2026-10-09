plugins {
    id("eia.jvm-service")
}

application {
    // 在庫の引当(注文 Saga の参加者。ADR-0029)。コマンドを読み、返事を Outbox で書く
    mainClass.set("io.eia.inventory.app.MainKt")
}

dependencies {
    implementation(project(":services:inventory:adapters"))
    implementation(project(":platform:observability"))
    implementation(project(":platform:schema-registry"))
    implementation(project(":platform:outbox"))
    implementation(project(":platform:inbox"))
    // DB のパスワード(SecretProvider。ADR-0019 §6)
    implementation(project(":platform:security"))
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.netty)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)
}
