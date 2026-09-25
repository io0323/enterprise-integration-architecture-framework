package io.eia.shared.canonical.common

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.money.CurrencyResolver
import io.eia.shared.kernel.money.Money
import io.eia.shared.kernel.money.Rate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * [Money] の JSON 表現: `{"amount": "1234.50", "currency": "USD"}`(ADR-0011 §1)。
 *
 * 通貨コードは [currencies] で解決する。小数桁数を超える金額や未知の通貨は丸めずに拒否する。
 * Canonical Model では `@Contextual` で参照し、[CanonicalCodec] が登録する。
 */
public class MoneySerializer(
    private val currencies: CurrencyResolver,
) : KSerializer<Money> {
    override val descriptor: SerialDescriptor = MoneyJson.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: Money,
    ) {
        encoder.encodeSerializableValue(MoneyJson.serializer(), MoneyJson(value.toDecimalString(), value.currency.code))
    }

    override fun deserialize(decoder: Decoder): Money {
        val json = decoder.decodeSerializableValue(MoneyJson.serializer())
        val currency = currencies.resolve(json.currency) ?: throw SerializationException("未知の通貨コードです: '${json.currency}'")
        // 金額の値は個人・取引情報になりうるため、エラーメッセージに含めない
        return when (val parsed = Money.parse(json.amount, currency)) {
            is Result.Ok -> parsed.value
            is Result.Err -> throw SerializationException(parsed.error.message)
        }
    }
}

@Serializable
@SerialName("Money")
private class MoneyJson(
    val amount: String,
    val currency: String,
)

/** [Rate] の JSON 表現: `{"numerator": 1, "denominator": 10}`(既約分数)。 */
public object RateSerializer : KSerializer<Rate> {
    override val descriptor: SerialDescriptor = RateJson.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: Rate,
    ) {
        encoder.encodeSerializableValue(RateJson.serializer(), RateJson(value.numerator, value.denominator))
    }

    override fun deserialize(decoder: Decoder): Rate {
        val json = decoder.decodeSerializableValue(RateJson.serializer())
        return when (val parsed = Rate.of(json.numerator, json.denominator)) {
            is Result.Ok -> parsed.value
            is Result.Err -> throw SerializationException(parsed.error.message)
        }
    }
}

@Serializable
@SerialName("Rate")
private class RateJson(
    val numerator: Long,
    val denominator: Long,
)
