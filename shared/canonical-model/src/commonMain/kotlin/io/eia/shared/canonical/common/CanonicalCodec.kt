package io.eia.shared.canonical.common

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.catching
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.money.CurrencyResolver
import io.eia.shared.kernel.money.Money
import io.eia.shared.kernel.money.Rate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

/**
 * Canonical Model の JSON の入口(ADR-0011 §5)。**受信点では必ず [decode] を使う**(CODING_STANDARDS)。
 *
 * - [decode] はデシリアライズと [Validatable.validate] を一緒に行い、構造・値域・業務整合の違反を [ValidationError] で返す。
 * - [encode] も送信前に [Validatable.validate] を通すため、不整合なデータは送信できない。
 * - `Money` と `Rate` は `@Contextual` で参照しているため、この Codec を通さない `Json` ではシリアライズできない。
 * - 前方互換のため未知のフィールドは無視する(INTEGRATION_STANDARDS §4)。
 *
 * @param currencies JSON の通貨コードから `Currency` を引く。扱う通貨を増やすときはここを差し替える。
 */
public class CanonicalCodec(
    currencies: CurrencyResolver = CurrencyResolver.COMMON,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            serializersModule =
                SerializersModule {
                    contextual(Money::class, MoneySerializer(currencies))
                    contextual(Rate::class, RateSerializer)
                }
        }

    public inline fun <reified T : Validatable<T>> decode(text: String): Result<T, DomainError> = decode(serializer<T>(), text)

    public fun <T : Validatable<T>> decode(
        deserializer: DeserializationStrategy<T>,
        text: String,
    ): Result<T, DomainError> = catching(::toDomainError) { json.decodeFromString(deserializer, text) }.flatMap { it.validate() }

    public inline fun <reified T : Validatable<T>> encode(value: T): Result<String, ValidationError> = encode(serializer<T>(), value)

    public fun <T : Validatable<T>> encode(
        serializer: SerializationStrategy<T>,
        value: T,
    ): Result<String, ValidationError> = value.validate().map { json.encodeToString(serializer, it) }

    private fun toDomainError(e: Exception): DomainError =
        when (e) {
            // kotlinx.serialization の例外メッセージは入力(JSON input: ...)を含むため、その部分を除いてから返す
            is SerializationException, is IllegalArgumentException -> {
                ValidationError.of(
                    "$",
                    e.message
                        .orEmpty()
                        .substringBefore("\nJSON input:")
                        .ifEmpty { "JSON を解釈できません" },
                )
            }

            else -> {
                UnexpectedError.from(e)
            }
        }
}
