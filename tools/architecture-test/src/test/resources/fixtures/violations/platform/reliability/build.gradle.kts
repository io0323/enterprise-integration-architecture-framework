// 違反: AWS SDK・Parquet の依存は platform/file-transfer(と audit)だけで宣言する
dependencies {
    implementation(libs.aws.sdk.s3)
    implementation(libs.parquet.hadoop)
    implementation(libs.sshj)
    // 違反: MQTT の購読のライブラリは services/iot-bridge/adapters だけ
    implementation(libs.hivemq.mqtt.client)
    // テストの依存は対象外
    testImplementation(libs.aws.sdk.s3)
}
