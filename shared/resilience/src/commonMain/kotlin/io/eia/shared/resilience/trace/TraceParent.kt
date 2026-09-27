package io.eia.shared.resilience.trace

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
import kotlin.random.Random

/**
 * W3C Trace Context の `traceparent`(Framework 14.1)。API はヘッダ、Event はメッセージヘッダ、
 * Batch / File は manifest・ジョブ属性で引き継ぐ。JVM(platform/observability)と KMP SDK(P11)が同じ解析・生成を使う(ADR-0004)。
 *
 * 形式は `{version}-{trace-id}-{parent-id}-{trace-flags}`。送信時は常に版 `00` で出力し、未定義のフラグは 0 にする([TraceFlags])。
 */
public data class TraceParent(
    public val traceId: TraceId,
    public val parentId: SpanId,
    public val flags: TraceFlags,
) {
    public val sampled: Boolean get() = flags.sampled

    /** 同じトレースで、このコンテキストを親とする新しい span の `traceparent` を作る。 */
    public fun child(random: Random = Random.Default): TraceParent = copy(parentId = SpanId.generate(random))

    /** ヘッダ値(版 `00`)。未定義のフラグは 0 にして出力する。 */
    public fun format(): String = "$VERSION-$traceId-$parentId-${flags.outgoing()}"

    override fun toString(): String = format()

    public companion object {
        public const val HEADER: String = "traceparent"
        private const val VERSION = "00"
        private const val INVALID_VERSION = "ff"

        /** 版 00 の長さ(`2 + 1 + 32 + 1 + 16 + 1 + 2`)。 */
        private const val V00_LENGTH = 55
        private val VERSION_FORMAT = Regex("^[0-9a-f]{2}$")

        /**
         * ヘッダ値を解析する。前後の OWS(RFC 9110 §5.6.3。SP と HTAB のみ)は取り除く。それ以外の空白や改行は不正な値として扱う。
         * - 版 `00` は長さが 55 文字ちょうどでなければならない。
         * - 未知の上位版は、先頭 55 文字を版 00 と同じ形式で読み、続きが `-` で始まる場合だけ受け付ける(W3C の前方互換)。
         * - trace-flags は版によらず sampled と random だけを残す(Level 2 §3.2.2.5.2・§3.2.2.5.3・§4.1.2。根拠は [TraceFlags])。
         * - 版 `ff`、大文字の 16 進、全 0 の trace-id / parent-id は拒否する。
         *
         * エラーメッセージには受信した値を含めない(ログに載るため)。
         */
        public fun parse(header: String): Result<TraceParent, ValidationError> {
            val value = header.trim { it == ' ' || it == '\t' }
            val version = value.take(2)
            return when {
                !VERSION_FORMAT.matches(version) || version == INVALID_VERSION -> {
                    invalid("版が不正です")
                }

                version == VERSION && value.length != V00_LENGTH -> {
                    invalid("版 00 の長さは $V00_LENGTH 文字です")
                }

                value.length < V00_LENGTH -> {
                    invalid("長さが足りません")
                }

                value.length > V00_LENGTH && value[V00_LENGTH] != '-' -> {
                    invalid("上位版の拡張部は '-' で区切られていません")
                }

                else -> {
                    parseFields(value.take(V00_LENGTH))
                }
            }
        }

        /**
         * 新しいトレースの起点を作る(入口で受信した `traceparent` がないとき)。
         *
         * random フラグを立てる。W3C Trace Context Level 2 §3.2.2.5.2 Random Trace ID Flag は、trace-id の右 7 バイトを
         * ランダムに生成した場合にこのフラグを立てるべき(SHOULD)としている。[TraceId.generate] は 16 バイトすべてを [random] から作るため、条件を満たす。
         */
        public fun generate(
            random: Random = Random.Default,
            sampled: Boolean = true,
        ): TraceParent =
            TraceParent(
                traceId = TraceId.generate(random),
                parentId = SpanId.generate(random),
                flags = TraceFlags.RANDOM.withSampled(sampled),
            )

        private fun parseFields(value: String): Result<TraceParent, ValidationError> {
            val parts = value.split('-')
            if (parts.size != FIELD_COUNT) return invalid("区切りが不正です")
            return TraceId.parse(parts[1]).flatMap { trace ->
                SpanId.parse(parts[2]).flatMap { parent ->
                    parseFlags(parts[FLAGS_INDEX]).map { TraceParent(trace, parent, it.retaining(TraceFlags.KNOWN_BITS)) }
                }
            }
        }

        private fun parseFlags(value: String): Result<TraceFlags, ValidationError> =
            if (VERSION_FORMAT.matches(value)) ok(TraceFlags(value.toInt(HEX_RADIX))) else invalid("trace-flags は小文字の 16 進 2 文字です")

        private const val FIELD_COUNT = 4
        private const val FLAGS_INDEX = 3

        private fun invalid(reason: String): Result<Nothing, ValidationError> = err(ValidationError.of(HEADER, reason))
    }
}
