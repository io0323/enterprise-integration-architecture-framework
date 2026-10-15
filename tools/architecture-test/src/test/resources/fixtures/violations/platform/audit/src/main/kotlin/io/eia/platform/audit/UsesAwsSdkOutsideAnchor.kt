package io.eia.platform.audit

// 違反: audit でも、アンカー(anchor パッケージ)の外では AWS SDK を使わない(完全修飾名での参照)
class UsesAwsSdkOutsideAnchor {
    val region = software.amazon.awssdk.regions.Region.US_EAST_1
}
