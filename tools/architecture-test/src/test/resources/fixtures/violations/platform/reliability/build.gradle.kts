// 違反: AWS SDK・Parquet の依存は platform/file-transfer(と audit)だけで宣言する
dependencies {
    implementation(libs.aws.sdk.s3)
    implementation(libs.parquet.hadoop)
    // テストの依存は対象外
    testImplementation(libs.aws.sdk.s3)
}
