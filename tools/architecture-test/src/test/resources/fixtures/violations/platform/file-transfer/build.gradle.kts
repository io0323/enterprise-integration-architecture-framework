dependencies {
    // 準拠: file-transfer は AWS SDK を宣言してよい
    implementation(libs.aws.sdk.s3)
    // 違反: Hadoop は compileOnly だけ(実行時のクラスパスに入れない。ADR-0033 §1)
    implementation(libs.hadoop.client.api)
}
