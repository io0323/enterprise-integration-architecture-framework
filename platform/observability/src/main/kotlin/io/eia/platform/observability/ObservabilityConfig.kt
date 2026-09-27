package io.eia.platform.observability

import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

/**
 * OTel の初期化の設定(ADR-0018 §1)。[of] か [fromEnvironment] で検証してから作る(ADR-0011)。
 *
 * @property otlpEndpoint OTLP/HTTP の送り先の基底 URL(例 `http://localhost:19318`)。`null` のときは OTLP に送らない(標準出力のログだけになる)。
 * @property samplingRatio 起点のトレースを記録する割合(0.0〜1.0)。親がある場合は親の判断に従う(ParentBased)。
 */
public data class ObservabilityConfig private constructor(
    public val serviceName: String,
    public val environment: String,
    public val otlpEndpoint: String?,
    public val samplingRatio: Double,
) {
    public companion object {
        /** OTel の標準の環境変数(OTLP の送り先・サービス名・サンプリング率)。 */
        public const val ENV_SERVICE_NAME: String = "OTEL_SERVICE_NAME"
        public const val ENV_OTLP_ENDPOINT: String = "OTEL_EXPORTER_OTLP_ENDPOINT"
        public const val ENV_SAMPLING_RATIO: String = "OTEL_TRACES_SAMPLER_ARG"

        /** 環境の名前(`deployment.environment.name`)。 */
        public const val ENV_ENVIRONMENT: String = "EIA_ENVIRONMENT"

        private const val DEFAULT_ENVIRONMENT = "local"
        private val ENDPOINT = Regex("^https?://[^\\s/]+(/\\S*)?$")

        public fun of(
            serviceName: String,
            environment: String = DEFAULT_ENVIRONMENT,
            otlpEndpoint: String? = null,
            samplingRatio: Double = 1.0,
        ): Result<ObservabilityConfig, ValidationError> {
            val violations =
                buildList {
                    if (serviceName.isBlank()) add(FieldViolation("serviceName", "空にできません"))
                    if (environment.isBlank()) add(FieldViolation("environment", "空にできません"))
                    if (otlpEndpoint != null && !ENDPOINT.matches(otlpEndpoint)) {
                        add(FieldViolation("otlpEndpoint", "http:// か https:// で始まる URL です"))
                    }
                    if (samplingRatio.isNaN() || samplingRatio !in 0.0..1.0) add(FieldViolation("samplingRatio", "0.0〜1.0 です"))
                }
            return if (violations.isEmpty()) {
                ok(ObservabilityConfig(serviceName, environment, otlpEndpoint?.trimEnd('/'), samplingRatio))
            } else {
                err(ValidationError(violations))
            }
        }

        /** 環境変数から作る。値はエラーメッセージに含めない。 */
        public fun fromEnvironment(env: Map<String, String> = System.getenv()): Result<ObservabilityConfig, ValidationError> {
            val ratio = env[ENV_SAMPLING_RATIO]?.takeIf { it.isNotBlank() }
            val parsedRatio = ratio?.toDoubleOrNull()
            if (ratio != null && parsedRatio == null) return err(ValidationError.of("samplingRatio", "数値ではありません"))
            return of(
                serviceName = env[ENV_SERVICE_NAME].orEmpty(),
                environment = env[ENV_ENVIRONMENT]?.takeIf { it.isNotBlank() } ?: DEFAULT_ENVIRONMENT,
                otlpEndpoint = env[ENV_OTLP_ENDPOINT]?.takeIf { it.isNotBlank() },
                samplingRatio = parsedRatio ?: 1.0,
            )
        }
    }
}
