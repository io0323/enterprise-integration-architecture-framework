package io.eia.platform.reliability

// 違反: AWS SDK は platform/file-transfer と platform/audit のアンカーだけ(ADR-0032 §6)
import software.amazon.awssdk.services.s3.S3Client

class UsesAwsSdk(
    val client: S3Client,
)
