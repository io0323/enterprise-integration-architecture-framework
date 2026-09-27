package io.eia.platform.observability.context

/** Correlation ID を運ぶヘッダ(INTEGRATION_STANDARDS §2)。 */
public object CorrelationHeaders {
    /** HTTP のヘッダ。 */
    public const val X_CORRELATION_ID: String = "X-Correlation-Id"
}
