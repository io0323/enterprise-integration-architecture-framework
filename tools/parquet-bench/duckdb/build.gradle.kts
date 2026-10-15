// DuckDB JDBC。全プラットフォームのネイティブを含む jar の代わりに、イメージのプラットフォームの分類子の jar を使う
// (-Pbench.platform=linux_amd64 | linux_arm64。既定は全部入りの jar)
plugins {
    application
}

val platform = providers.gradleProperty("bench.platform").orNull

dependencies {
    implementation(project(":common"))
    if (platform == null) {
        implementation("org.duckdb:duckdb_jdbc:1.5.6.0")
    } else {
        implementation("org.duckdb:duckdb_jdbc:1.5.6.0:$platform")
    }
}

application {
    mainClass.set("io.eia.tools.parquetbench.duckdb.MainKt")
    applicationName = "bench"
}
