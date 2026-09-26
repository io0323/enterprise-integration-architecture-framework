package io.eia.platform.observability

// 違反: import を使わず、完全修飾名で platform/test-support を参照
val collectorImageQualified = io.eia.platform.testsupport.InfraImages.get("OTEL_COLLECTOR_IMAGE")
