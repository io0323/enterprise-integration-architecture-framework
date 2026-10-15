dependencies {
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3)
    implementation(libs.parquet.hadoop)
    compileOnly(libs.hadoop.client.api) { isTransitive = false }
}
