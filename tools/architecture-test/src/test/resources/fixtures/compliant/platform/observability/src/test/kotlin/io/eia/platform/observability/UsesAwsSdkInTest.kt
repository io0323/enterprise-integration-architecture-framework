package io.eia.platform.observability

// 準拠: テストのソースセットでは AWS SDK を使ってよい(バケットの作成など)
import software.amazon.awssdk.services.s3.S3Client

class UsesAwsSdkInTest(
    val client: S3Client,
)
