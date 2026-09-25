package io.eia.shared.canonical.common

import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

/**
 * 値域と業務整合を検証できる Canonical エンティティ(Framework 15.2)。
 *
 * デシリアライズはコンストラクタでの検証を通らないため、受信点では [CanonicalCodec.decode] で
 * デシリアライズと [validate] を必ず一緒に行う(ADR-0011 §5)。
 */
public interface Validatable<out T> {
    /** 違反をすべて集めて返す。違反がなければ自分自身を返す。 */
    public fun validate(): Result<T, ValidationError>
}

/** 検証の違反を集めるビルダー。 */
public class Violations
    @PublishedApi
    internal constructor() {
        private val collected = mutableListOf<FieldViolation>()

        public fun check(
            condition: Boolean,
            field: String,
            reason: () -> String,
        ) {
            if (!condition) collected += FieldViolation(field, reason())
        }

        /** 子要素の検証結果を、項目パスに [prefix] を付けて取り込む。 */
        public fun include(
            prefix: String,
            result: Result<*, ValidationError>,
        ) {
            if (result is Result.Err) collected += result.error.violations.map { it.copy(field = "$prefix.${it.field}") }
        }

        @PublishedApi
        internal fun <T> toResult(value: T): Result<T, ValidationError> =
            if (collected.isEmpty()) ok(value) else err(ValidationError(collected.toList()))
    }

public inline fun <T> validating(
    value: T,
    block: Violations.() -> Unit,
): Result<T, ValidationError> = Violations().apply(block).toResult(value)
