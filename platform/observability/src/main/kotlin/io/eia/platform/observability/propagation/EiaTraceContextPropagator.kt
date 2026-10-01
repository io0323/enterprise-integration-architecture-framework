package io.eia.platform.observability.propagation

import io.eia.platform.observability.context.toTraceParent
import io.eia.shared.kernel.Result
import io.eia.shared.resilience.trace.SpanId
import io.eia.shared.resilience.trace.TraceFlags
import io.eia.shared.resilience.trace.TraceId
import io.eia.shared.resilience.trace.TraceParent
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.api.trace.TraceFlags as OtelTraceFlags

/**
 * W3C Trace Context の Propagator。`traceparent` の解析と生成に `shared/resilience` の [TraceParent] を使い、
 * P11 の KMP SDK(native / js)と同じ規則で伝搬する(ADR-0018 §1)。
 *
 * - 正しい `traceparent` / `tracestate` に対する抽出・注入の結果は、OTel 標準の `W3CTraceContextPropagator` と同じ(property test で検査)。
 * - 不正な入力の扱いは [TraceParent.parse] に従うため、OTel 標準と一部が異なる(前後の OWS を許す、未定義のフラグを 0 にする。ADR-0018 §1)。
 * - `tracestate` は W3C の規則で検証し、1 つでも不正なメンバーがあれば全体を捨てる(`traceparent` は使う)。
 */
public object EiaTraceContextPropagator : TextMapPropagator {
    public const val TRACESTATE: String = "tracestate"

    /** W3C Trace Context §3.3(tracestate): メンバーは 32 個まで。 */
    private const val MAX_TRACESTATE_MEMBERS = 32

    /** W3C Trace Context §3.3(tracestate): 512 文字を超える値は切り詰めてよい。ここでは全体を捨てる。 */
    private const val MAX_TRACESTATE_LENGTH = 512

    private val FIELDS = listOf(TraceParent.HEADER, TRACESTATE)

    override fun fields(): Collection<String> = FIELDS

    override fun <C : Any?> inject(
        context: Context,
        carrier: C?,
        setter: TextMapSetter<C>,
    ) {
        val spanContext = Span.fromContext(context).spanContext
        if (carrier == null || !spanContext.isValid) return
        val traceParent = spanContext.toTraceParent() ?: return
        setter.set(carrier, TraceParent.HEADER, traceParent.format())
        val traceState = spanContext.traceState
        if (!traceState.isEmpty) setter.set(carrier, TRACESTATE, traceState.encode())
    }

    override fun <C : Any?> extract(
        context: Context,
        carrier: C?,
        getter: TextMapGetter<C>,
    ): Context {
        val header = carrier?.let { getter.get(it, TraceParent.HEADER) }
        val traceParent = header?.let { (TraceParent.parse(it) as? Result.Ok)?.value } ?: return context
        val traceState = getter.get(carrier, TRACESTATE)?.let(::decodeTraceState) ?: TraceState.getDefault()
        val remote =
            SpanContext.createFromRemoteParent(
                traceParent.traceId.value,
                traceParent.parentId.value,
                OtelTraceFlags.fromByte(traceParent.flags.value.toByte()),
                traceState,
            )
        return context.with(Span.wrap(remote))
    }

    override fun toString(): String = "EiaTraceContextPropagator"

    private fun TraceState.encode(): String = buildList { forEach { key, value -> add("$key=$value") } }.joinToString(",")

    /** W3C Trace Context §3.3(tracestate): `list-member` を `,` で区切る。前後の OWS と空のメンバーは無視する。不正なら既定値(空)を返す。 */
    private fun decodeTraceState(header: String): TraceState {
        val members = header.split(',').map { it.trim(' ', '\t') }.filter { it.isNotEmpty() }
        val pairs = members.mapNotNull(::splitMember)
        val withinLimits = header.length <= MAX_TRACESTATE_LENGTH && members.size <= MAX_TRACESTATE_MEMBERS && pairs.size == members.size
        // TraceStateBuilder は後に入れたメンバーを先頭に並べるため、逆順に入れて受信した順を保つ。
        // 不正な key / value と重複した key は TraceStateBuilder が黙って捨てるので、件数が減っていれば不正とみなして全体を捨てる
        val built =
            if (withinLimits) {
                TraceState
                    .builder()
                    .apply {
                        pairs.asReversed().forEach { (key, value) ->
                            put(key, value)
                        }
                    }.build()
            } else {
                null
            }
        return built?.takeIf { it.size() == members.size } ?: TraceState.getDefault()
    }

    private fun splitMember(member: String): Pair<String, String>? {
        val separator = member.indexOf('=')
        return if (separator > 0) member.substring(0, separator) to member.substring(separator + 1) else null
    }
}
