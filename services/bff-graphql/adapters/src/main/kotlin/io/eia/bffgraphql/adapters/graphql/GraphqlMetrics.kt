package io.eia.bffgraphql.adapters.graphql

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter

/**
 * GraphQL のメトリクス(ADR-0043 §6・§4・§7)。部分応答は HTTP 200 なので、RED の Errors には出ない。ここで別に数える。
 *
 * | メトリクス | 属性 |
 * |---|---|
 * | `eia.graphql.partial_responses` | `service`(読めなかったサービス) |
 * | `eia.graphql.rejected_queries` | `limit`(超えた上限) |
 * | `eia.graphql.sensitive_field_reads` | `field`・`client` |
 */
public class GraphqlMetrics(
    meter: Meter,
) {
    private val partial: LongCounter =
        meter
            .counterBuilder("eia.graphql.partial_responses")
            .setUnit("{response}")
            .setDescription("一部のサービスを読めず、取れた部分だけを返した応答の数(サービスごと)")
            .build()
    private val rejected: LongCounter =
        meter
            .counterBuilder("eia.graphql.rejected_queries")
            .setUnit("{query}")
            .setDescription("上限を超えて実行の前に拒否したクエリの数")
            .build()
    private val sensitive: LongCounter =
        meter
            .counterBuilder("eia.graphql.sensitive_field_reads")
            .setUnit("{read}")
            .setDescription("個人情報の項目を読んだ件数(値は記録しない)")
            .build()

    internal fun recordPartial(service: String) = partial.add(1, Attributes.of(SERVICE, service))

    internal fun recordRejected(limit: String) = rejected.add(1, Attributes.of(LIMIT, limit))

    internal fun recordSensitiveRead(
        coordinate: FieldCoordinate,
        client: String?,
    ) = sensitive.add(1, Attributes.of(FIELD, coordinate.toString(), CLIENT, client.orEmpty()))

    private companion object {
        val SERVICE: AttributeKey<String> = AttributeKey.stringKey("service")
        val LIMIT: AttributeKey<String> = AttributeKey.stringKey("limit")
        val FIELD: AttributeKey<String> = AttributeKey.stringKey("field")
        val CLIENT: AttributeKey<String> = AttributeKey.stringKey("client")
    }
}
