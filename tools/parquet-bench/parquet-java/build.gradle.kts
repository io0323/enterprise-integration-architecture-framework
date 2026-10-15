// parquet-java(Hadoop なし)。parquet-hadoop の Hadoop の依存は provided なので、実行時のクラスパスに Hadoop は入らない
plugins {
    application
}

dependencies {
    implementation(project(":common"))
    implementation("org.apache.parquet:parquet-hadoop:1.18.1") {
        // zstd だけを使う(ZstdCodecFactory)。snappy-java(ネイティブ)・aircompressor・commons-pool は既定の CodecFactory の部品で、使わない
        exclude(group = "org.xerial.snappy", module = "snappy-java")
        exclude(group = "io.airlift", module = "aircompressor")
        exclude(group = "commons-pool", module = "commons-pool")
    }
    // WriteSupport・ParquetWriter.Builder の抽象メソッドの引数が Hadoop の Configuration なので、コンパイルにだけ使う
    compileOnly("org.apache.hadoop:hadoop-client-api:3.5.0") { isTransitive = false }
    runtimeOnly(libs.slf4j.nop)
}

application {
    mainClass.set("io.eia.tools.parquetbench.parquetjava.MainKt")
    applicationName = "bench"
}
