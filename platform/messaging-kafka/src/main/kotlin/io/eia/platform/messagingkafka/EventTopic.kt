package io.eia.platform.messagingkafka

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

/**
 * イベント・コマンドのトピック名(INTEGRATION_STANDARDS §1)。`{domain}.{entity}.{event}.v{n}`(コマンドは event が `cmd-{command}`。ADR-0006)。
 * 規則は contract-check の命名の検査(`Naming.checkTopic`)と同じ。DLQ(`{topic}.dlq`)はここでは扱わない(P07)。
 */
@JvmInline
public value class EventTopic private constructor(
    public val name: String,
) {
    /** CloudEvents の `type`。トピック名から `.v{n}` を除いたもの(例 `sales.order.created`)。AsyncAPI の StandardHeaders と同じ。 */
    public val ceType: String get() = name.substringBeforeLast('.')

    override fun toString(): String = name

    public companion object {
        private const val SEGMENT = "[a-z][a-z0-9]*(?:-[a-z0-9]+)*"
        private val TOPIC = Regex("^$SEGMENT\\.$SEGMENT\\.($SEGMENT)\\.v[1-9][0-9]*$")
        private val COMMAND_SEGMENT = Regex("^cmd-$SEGMENT$")

        public fun parse(name: String): Result<EventTopic, ValidationError> {
            val event = TOPIC.matchEntire(name)?.groupValues?.get(1)
            return when {
                event == null -> err(ValidationError.of("topic", "{domain}.{entity}.{event}.v{n}(小文字・kebab-case)ではありません"))

                event.startsWith(
                    "cmd",
                ) && !COMMAND_SEGMENT.matches(event) -> err(ValidationError.of("topic", "コマンドは cmd-{command} の形にしてください"))

                else -> ok(EventTopic(name))
            }
        }

        /** 定数のトピック名から作る。形式が違えば例外(設定・コードの誤り)。 */
        public fun of(name: String): EventTopic =
            when (val parsed = parse(name)) {
                is Result.Ok -> parsed.value
                is Result.Err -> throw IllegalArgumentException("トピック名 '$name' が不正です: ${parsed.error.message}")
            }
    }
}
