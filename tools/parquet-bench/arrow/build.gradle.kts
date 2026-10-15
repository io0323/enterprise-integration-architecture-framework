// Apache Arrow の dataset(JNI で Arrow C++ の Parquet の書き手を使う)。ネイティブは jar に全プラットフォーム分が入っている
plugins {
    application
}

dependencies {
    implementation(project(":common"))
    implementation("org.apache.arrow:arrow-dataset:19.0.0")
    runtimeOnly("org.apache.arrow:arrow-memory-netty:19.0.0")
    runtimeOnly(libs.slf4j.nop)
}

application {
    mainClass.set("io.eia.tools.parquetbench.arrow.MainKt")
    applicationName = "bench"
    // Arrow のメモリの管理が java.nio の内部に触れる(Arrow Java の要件)
    applicationDefaultJvmArgs = listOf("--add-opens=java.base/java.nio=ALL-UNNAMED")
}
