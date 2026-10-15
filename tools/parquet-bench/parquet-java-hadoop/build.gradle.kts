// parquet-java + hadoop-common(Hadoop の Configuration・圧縮の codec を使う、従来の使い方)。コードは parquet-java と同じ
plugins {
    application
}

sourceSets {
    main {
        kotlin.srcDir("../parquet-java/src/main/kotlin")
    }
}

dependencies {
    implementation(project(":common"))
    implementation("org.apache.parquet:parquet-hadoop:1.18.1")
    implementation("org.apache.hadoop:hadoop-common:3.5.0")
    runtimeOnly(libs.slf4j.nop)
}

application {
    mainClass.set("io.eia.tools.parquetbench.parquetjava.MainKt")
    applicationName = "bench"
}
