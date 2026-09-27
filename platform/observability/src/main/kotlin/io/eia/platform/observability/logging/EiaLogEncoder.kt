package io.eia.platform.observability.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.encoder.EncoderBase
import io.eia.platform.observability.context.LogKeys
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** 標準出力のログの形式。環境変数 `EIA_LOG_FORMAT` で選ぶ(既定は [JSON])。 */
public enum class LogFormat {
    /** 構造化 JSON(1 行 1 レコード)。Framework 14.1 の既定。 */
    JSON,

    /** 人が読むための 1 行の形式(ローカルの開発用)。 */
    CONSOLE,
    ;

    public companion object {
        public const val ENV: String = "EIA_LOG_FORMAT"

        /** `json` / `console`(大文字小文字を区別しない)。それ以外は `null`。 */
        public fun parse(value: String?): LogFormat? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * 標準出力用の logback のエンコーダ(CODING_STANDARDS「ロギング」。ADR-0018 §3)。
 *
 * JSON の必須キー: `timestamp`, `level`, `service`, `trace_id`, `span_id`, `correlation_id`, `integration_id`, `message`。
 * 値のないキーは `null` で出す(キーの有無で検索が変わらないように)。ほかに `logger`, `thread`, `exception` と、上記以外の MDC を出す。
 *
 * メッセージ・例外・MDC の値は [Masking] を通す。ID のキー([LogKeys])は形式を検証済みの値なので、そのまま出す
 * (数字だけの span_id がカード番号と誤判定されないように)。
 *
 * logback.xml の設定項目: `service`(サービス名)、`format`(`json` / `console`。不正な値は `json` にして警告する)。
 */
public class EiaLogEncoder : EncoderBase<ILoggingEvent>() {
    public var service: String = "unknown"
    public var format: String = LogFormat.JSON.name

    private var resolvedFormat: LogFormat = LogFormat.JSON

    override fun start() {
        val parsed = LogFormat.parse(format)
        if (parsed == null) addWarn("${LogFormat.ENV} は json か console です。json で出力します")
        resolvedFormat = parsed ?: LogFormat.JSON
        super.start()
    }

    override fun headerBytes(): ByteArray? = null

    override fun footerBytes(): ByteArray? = null

    override fun encode(event: ILoggingEvent): ByteArray {
        val line =
            when (resolvedFormat) {
                LogFormat.JSON -> toJson(event).toString()
                LogFormat.CONSOLE -> toConsole(event)
            }
        return (line + "\n").toByteArray(Charsets.UTF_8)
    }

    internal fun toJson(event: ILoggingEvent): JsonObject {
        val mdc = event.mdcPropertyMap.orEmpty()
        return buildJsonObject {
            put("timestamp", event.timestamp().toString())
            put("level", event.level.toString())
            put("service", service)
            LogKeys.ALL.forEach { key -> put(key, mdc[key]?.let(::JsonPrimitive) ?: JsonNull) }
            put("message", Masking.mask(event.formattedMessage.orEmpty()))
            put("logger", event.loggerName)
            put("thread", event.threadName)
            event.throwableProxy?.let { put("exception", Masking.mask(it.render())) }
            mdc.filterKeys { it !in LogKeys.ALL }.forEach { (key, value) -> put(key, Masking.mask(value.orEmpty())) }
        }
    }

    private fun toConsole(event: ILoggingEvent): String {
        val mdc = event.mdcPropertyMap.orEmpty()
        val ids = LogKeys.ALL.mapNotNull { key -> mdc[key]?.let { "$key=$it" } }.joinToString(" ")
        return buildString {
            append(event.timestamp()).append(' ')
            append(event.level.toString().padEnd(LEVEL_WIDTH)).append(' ')
            append('[').append(service).append("] ")
            append(event.loggerName.substringAfterLast('.')).append(" - ")
            append(Masking.mask(event.formattedMessage.orEmpty()))
            if (ids.isNotEmpty()) append(" (").append(ids).append(')')
            event.throwableProxy?.let { append('\n').append(Masking.mask(it.render())) }
        }
    }

    private companion object {
        const val LEVEL_WIDTH = 5

        /** UTC の ISO-8601(logback 1.3 以降はナノ秒まで持つ)。 */
        fun ILoggingEvent.timestamp(): Instant = instant ?: Instant.ofEpochMilli(timeStamp)

        fun IThrowableProxy.render(): String = ThrowableProxyUtil.asString(this)
    }
}
