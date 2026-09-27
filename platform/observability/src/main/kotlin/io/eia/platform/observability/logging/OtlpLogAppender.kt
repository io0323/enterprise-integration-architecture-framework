package io.eia.platform.observability.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.UnsynchronizedAppenderBase
import io.eia.platform.observability.context.LogKeys
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.LoggerProvider
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import io.opentelemetry.semconv.ExceptionAttributes
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * logback のイベントを OTel の Logs API(安定版)で OTLP に送るアペンダ(ADR-0018 §3)。
 * OTel の計装ライブラリ `opentelemetry-logback-appender-1.0` は alpha のみのため使わない(ADR-0018 §4)。
 *
 * 標準出力([EiaLogEncoder])と並べて root に付ける。本文・例外・MDC の値は [Masking] を通す。
 *
 * OTel の初期化([install])より前のイベントは、[bufferSize] 件までメモリに溜め、初期化時に元の時刻のまま送る。
 * 溢れた分は捨て、件数を初期化時に警告として記録する(ADR-0018 §3)。標準出力には、初期化の前後によらず常に出る。
 */
public class OtlpLogAppender : UnsynchronizedAppenderBase<ILoggingEvent>() {
    public var bufferSize: Int = DEFAULT_BUFFER_SIZE

    private val lock = Any()
    private val pending = ArrayDeque<ILoggingEvent>()
    private var dropped = 0

    @Volatile
    private var provider: LoggerProvider? = null

    override fun append(event: ILoggingEvent) {
        if (event.loggerName.startsWith(OTEL_LOGGER_PREFIX)) return // 送信部自身のログを送り返さない
        val current = provider
        if (current != null) {
            emit(current, event, Context.current())
            return
        }
        synchronized(lock) {
            val installed = provider
            when {
                installed != null -> {
                    emit(installed, event, Context.current())
                }

                pending.size < bufferSize -> {
                    event.prepareForDeferredProcessing()
                    pending.addLast(event)
                }

                else -> {
                    dropped++
                }
            }
        }
    }

    /** OTel を設定し、溜めていたイベントを送る。2 回目以降は送り先を差し替える。 */
    internal fun install(loggerProvider: LoggerProvider) {
        synchronized(lock) {
            while (pending.isNotEmpty()) emit(loggerProvider, pending.removeFirst(), Context.root())
            if (dropped > 0) {
                loggerProvider
                    .get(OtlpLogAppender::class.java.name)
                    .logRecordBuilder()
                    .setSeverity(Severity.WARN)
                    .setSeverityText(Level.WARN.toString())
                    .setBody("OTel の初期化前のログを $dropped 件破棄しました(bufferSize=$bufferSize)")
                    .emit()
                addWarn("OTel の初期化前のログを $dropped 件破棄しました")
                dropped = 0
            }
            provider = loggerProvider
        }
    }

    internal fun pendingCount(): Int = synchronized(lock) { pending.size }

    private fun emit(
        loggerProvider: LoggerProvider,
        event: ILoggingEvent,
        current: Context,
    ) {
        val mdc = event.mdcPropertyMap.orEmpty()
        val builder =
            loggerProvider
                .get(event.loggerName)
                .logRecordBuilder()
                .setTimestamp(event.instant ?: Instant.ofEpochMilli(event.timeStamp))
                .setSeverity(event.level.toSeverity())
                .setSeverityText(event.level.toString())
                .setBody(Masking.mask(event.formattedMessage.orEmpty()))
                .setAllAttributes(attributes(event, mdc))
        spanContextOf(mdc, current)?.let { builder.setContext(Context.root().with(Span.wrap(it))) }
        builder.emit()
    }

    private fun attributes(
        event: ILoggingEvent,
        mdc: Map<String, String>,
    ): Attributes {
        val attributes = Attributes.builder()
        attributes.put(LOGGER, event.loggerName)
        attributes.put(THREAD, event.threadName)
        mdc[LogKeys.CORRELATION_ID]?.let { attributes.put(CORRELATION_ID, it) }
        mdc[LogKeys.INTEGRATION_ID]?.let { attributes.put(INTEGRATION_ID, it) }
        mdc
            .filterKeys { it !in LogKeys.ALL }
            .forEach { (key, value) -> attributes.put(AttributeKey.stringKey(key), Masking.mask(value.orEmpty())) }
        event.throwableProxy?.let { proxy ->
            attributes.put(ExceptionAttributes.EXCEPTION_TYPE, proxy.className)
            proxy.message?.let { attributes.put(ExceptionAttributes.EXCEPTION_MESSAGE, Masking.mask(it)) }
            attributes.put(ExceptionAttributes.EXCEPTION_STACKTRACE, Masking.mask(ThrowableProxyUtil.asString(proxy)))
        }
        return attributes.build()
    }

    /** MDC の trace_id / span_id(溜めていたイベントを含む)を優先し、なければ記録時の現在の span を使う。 */
    private fun spanContextOf(
        mdc: Map<String, String>,
        current: Context,
    ): SpanContext? {
        val traceId = mdc[LogKeys.TRACE_ID]
        val spanId = mdc[LogKeys.SPAN_ID]
        if (traceId != null && spanId != null) {
            return SpanContext.create(traceId, spanId, TraceFlags.getDefault(), TraceState.getDefault()).takeIf { it.isValid }
        }
        return Span.fromContext(current).spanContext.takeIf { it.isValid }
    }

    public companion object {
        public const val DEFAULT_BUFFER_SIZE: Int = 1_000
        private const val OTEL_LOGGER_PREFIX = "io.opentelemetry"

        private val LOGGER = AttributeKey.stringKey("logger")
        private val THREAD = AttributeKey.stringKey("thread.name")
        private val CORRELATION_ID = AttributeKey.stringKey(LogKeys.CORRELATION_ID)
        private val INTEGRATION_ID = AttributeKey.stringKey(LogKeys.INTEGRATION_ID)

        /** [loggerContext] に付いているすべての [OtlpLogAppender] に OTel を設定する。付いていなければ何もしない。 */
        public fun install(
            loggerProvider: LoggerProvider,
            loggerContext: LoggerContext? = LoggerFactory.getILoggerFactory() as? LoggerContext,
        ) {
            appendersIn(loggerContext).forEach { it.install(loggerProvider) }
        }

        internal fun appendersIn(loggerContext: LoggerContext?): List<OtlpLogAppender> =
            loggerContext
                ?.loggerList
                .orEmpty()
                .flatMap { logger ->
                    logger
                        .iteratorForAppenders()
                        .asSequence()
                        .filterIsInstance<OtlpLogAppender>()
                        .toList()
                }.distinct()

        private fun Level.toSeverity(): Severity =
            when (toInt()) {
                Level.ERROR_INT -> Severity.ERROR
                Level.WARN_INT -> Severity.WARN
                Level.INFO_INT -> Severity.INFO
                Level.DEBUG_INT -> Severity.DEBUG
                else -> Severity.TRACE
            }
    }
}
