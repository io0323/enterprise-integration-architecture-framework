// 書いたファイルを、別の実装(parquet-java と DuckDB)で読み直して確かめる
plugins {
    application
}

dependencies {
    implementation(project(":common"))
    implementation("org.apache.parquet:parquet-hadoop:1.18.1")
    implementation("org.duckdb:duckdb_jdbc:1.5.6.0")
    // 読み直しはホストで行う。どの圧縮でも読めるよう、Hadoop の codec を使う
    // (ParquetFileReader の既定の設定は mapreduce のクラスも参照する)
    runtimeOnly("org.apache.hadoop:hadoop-common:3.5.0")
    runtimeOnly("org.apache.hadoop:hadoop-mapreduce-client-core:3.5.0")
    runtimeOnly(libs.slf4j.nop)
}

application {
    mainClass.set("io.eia.tools.parquetbench.verify.MainKt")
    applicationName = "verify"
}
