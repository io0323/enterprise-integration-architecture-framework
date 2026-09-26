package io.eia.platform.observability

// 準拠: 統合テストのソースセットから platform/test-support を参照
import io.eia.platform.testsupport.InfraImages

val collectorImage = InfraImages.get("OTEL_COLLECTOR_IMAGE")
