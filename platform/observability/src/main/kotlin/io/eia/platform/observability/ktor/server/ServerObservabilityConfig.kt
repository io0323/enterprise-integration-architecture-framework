package io.eia.platform.observability.ktor.server

import io.eia.platform.observability.ObservabilityRuntime

/** [ServerObservability] の設定。 */
public class ServerObservabilityConfig {
    /** 初期化済みの OTel(必須)。 */
    public var runtime: ObservabilityRuntime? = null

    /** 連携カタログの ID(`contracts/catalog/{id}.yaml` の `id`)。ログの `integration_id` とメトリクスの属性になる。 */
    public var integrationId: String? = null
}
