package io.eia.platform.observability

// 違反: 本番コード(main)から platform/test-support を参照
import io.eia.platform.testsupport.InfraImages

val collectorImage = InfraImages.get("OTEL_COLLECTOR_IMAGE")
