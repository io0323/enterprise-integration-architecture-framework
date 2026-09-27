package io.eia.platform.observability.ktor.client

import io.eia.platform.observability.ObservabilityRuntime

/** [ClientObservability] の設定。 */
public class ClientObservabilityConfig {
    /** 初期化済みの OTel(必須)。 */
    public var runtime: ObservabilityRuntime? = null
}
