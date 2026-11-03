// 違反: AWS SDK・Parquet の依存は platform/file-transfer(と audit)だけで宣言する
dependencies {
    implementation(libs.aws.sdk.s3)
    implementation(libs.parquet.hadoop)
    implementation(libs.sshj)
    // 違反: MQTT の購読のライブラリは services/iot-bridge/adapters だけ
    implementation(libs.hivemq.mqtt.client)
    // 違反: gRPC の依存は platform/grpc と platform/grpc-stubs だけ
    implementation(libs.grpc.netty.shaded)
    // 違反: GraphQL の依存は BFF の adapters と contract-check だけ
    implementation(libs.graphql.java)
    // テストの依存は対象外
    testImplementation(libs.aws.sdk.s3)
}
