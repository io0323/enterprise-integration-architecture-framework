package io.eia.b2bgateway.domain.validation

import io.eia.b2bgateway.domain.edifact.CharacterSet
import io.eia.b2bgateway.domain.edifact.Segment
import io.eia.b2bgateway.domain.spec.CharacterType
import io.eia.b2bgateway.domain.spec.DataFormat
import io.eia.b2bgateway.domain.spec.ElementDefinition
import io.eia.b2bgateway.domain.spec.SegmentDefinition
import io.eia.b2bgateway.domain.spec.Semantic
import io.eia.b2bgateway.domain.spec.Status

/**
 * セグメントのデータ要素を、仕様の定義で検査する(スキーマの段階。ADR-0041 §4)。状態(M・C・X)・数・形式(種類と長さ)・コード値・日付と時刻、
 * 文字セットの外の文字を見る。誤りは位置(タグの次のデータ要素が 1、構成要素も 1 から)とコード(0085)で返す。
 */
public object ElementCheck {
    private const val DAYS_IN_LONG_MONTH = 31
    private const val DATE_LENGTH = 8
    private const val TIME_LENGTH = 4
    private const val MAX_HOUR = 23
    private const val MAX_MINUTE = 59
    private const val FEBRUARY = 2
    private const val DAYS_IN_FEBRUARY = 28

    @Suppress("MagicNumber") // 30 日の月(4・6・9・11 月)
    private val SHORT_MONTHS = setOf(4, 6, 9, 11)

    public fun check(
        definition: SegmentDefinition,
        segment: Segment,
        characterSet: CharacterSet,
        decimal: Char,
    ): List<ElementError> {
        val errors = mutableListOf<ElementError>()
        if (segment.elements.size > definition.elements.size) {
            errors += ElementError(SyntaxErrorCode.TOO_MANY_CONSTITUENTS, definition.elements.size + 1)
        }
        definition.elements.forEachIndexed { index, element ->
            val position = index + 1
            val values = segment.elements.getOrNull(index).orEmpty()
            when (element) {
                is ElementDefinition.Simple -> {
                    if (values.size > 1) {
                        errors += ElementError(SyntaxErrorCode.TOO_MANY_CONSTITUENTS, position, 2)
                    } else {
                        checkValue(element, values.firstOrNull().orEmpty(), characterSet, decimal)?.let {
                            errors +=
                                ElementError(it, position)
                        }
                    }
                }

                is ElementDefinition.Composite -> {
                    errors += checkComposite(element, values, position, characterSet, decimal)
                }
            }
        }
        return errors
    }

    private fun checkComposite(
        element: ElementDefinition.Composite,
        values: List<String>,
        position: Int,
        characterSet: CharacterSet,
        decimal: Char,
    ): List<ElementError> {
        val present = values.any { it.isNotEmpty() }
        return when {
            !present && element.status == Status.MANDATORY -> {
                listOf(ElementError(SyntaxErrorCode.MISSING, position))
            }

            !present -> {
                emptyList()
            }

            element.status == Status.NOT_USED -> {
                listOf(ElementError(SyntaxErrorCode.NOT_SUPPORTED_IN_POSITION, position))
            }

            values.size > element.components.size -> {
                listOf(ElementError(SyntaxErrorCode.TOO_MANY_CONSTITUENTS, position, element.components.size + 1))
            }

            else -> {
                element.components.mapIndexedNotNull { index, component ->
                    checkValue(component, values.getOrNull(index).orEmpty(), characterSet, decimal)?.let {
                        ElementError(
                            it,
                            position,
                            index + 1,
                        )
                    }
                }
            }
        }
    }

    /** 単純データ要素の値 1 つの検査。誤りがなければ null。 */
    public fun checkValue(
        element: ElementDefinition.Simple,
        value: String,
        characterSet: CharacterSet,
        decimal: Char,
    ): SyntaxErrorCode? =
        when {
            value.isEmpty() -> if (element.status == Status.MANDATORY) SyntaxErrorCode.MISSING else null
            element.status == Status.NOT_USED -> SyntaxErrorCode.NOT_SUPPORTED_IN_POSITION
            value.any { !characterSet.allows(it) } -> SyntaxErrorCode.INVALID_CHARACTERS
            else -> checkFormat(element.format, value, decimal) ?: checkMeaning(element, value)
        }

    private fun checkMeaning(
        element: ElementDefinition.Simple,
        value: String,
    ): SyntaxErrorCode? =
        when {
            element.codes.isNotEmpty() && value !in element.codes -> SyntaxErrorCode.INVALID_VALUE
            element.semantic == Semantic.DATE && !isDate(value) -> SyntaxErrorCode.INVALID_VALUE
            element.semantic == Semantic.TIME && !isTime(value) -> SyntaxErrorCode.INVALID_VALUE
            else -> null
        }

    /** 形式(a・n・an と長さ)。n は先頭の負の符号と、UNA で指定した小数点を 1 つまで許し、長さに数えない(ISO 9735)。 */
    @Suppress("ReturnCount") // 種類の誤りは長さを数える前に返す
    private fun checkFormat(
        format: DataFormat,
        value: String,
        decimal: Char,
    ): SyntaxErrorCode? {
        val length =
            when (format.type) {
                CharacterType.ALPHABETIC -> {
                    if (value.any { it.isDigit() }) return SyntaxErrorCode.INVALID_CHARACTER_TYPE
                    value.length
                }

                CharacterType.NUMERIC -> {
                    numericError(value, decimal)?.let { return it }
                    value.count { it.isDigit() }
                }

                CharacterType.ALPHANUMERIC -> {
                    value.length
                }
            }
        return when {
            length > format.length -> SyntaxErrorCode.DATA_ELEMENT_TOO_LONG
            !format.upTo && length < format.length -> SyntaxErrorCode.DATA_ELEMENT_TOO_SHORT
            else -> null
        }
    }

    private fun numericError(
        value: String,
        decimal: Char,
    ): SyntaxErrorCode? {
        val unsigned = value.removePrefix("-")
        val marks = unsigned.count { !it.isDigit() }
        val otherMark = if (decimal == '.') ',' else '.'
        return when {
            unsigned.isEmpty() -> SyntaxErrorCode.INVALID_CHARACTER_TYPE
            unsigned.any { !it.isDigit() && it != decimal && it != otherMark } -> SyntaxErrorCode.INVALID_CHARACTER_TYPE
            otherMark in unsigned -> SyntaxErrorCode.INVALID_DECIMAL_NOTATION
            marks > 1 || unsigned.last() == decimal -> SyntaxErrorCode.INVALID_DECIMAL_NOTATION
            unsigned.first() == decimal -> SyntaxErrorCode.MISSING_DIGIT_BEFORE_DECIMAL
            else -> null
        }
    }

    @Suppress("MagicNumber") // CCYYMMDD の各部分の位置と、うるう年の規則(4・100・400 年)
    private fun isDate(value: String): Boolean {
        if (value.length != DATE_LENGTH || !value.all { it.isDigit() }) return false
        val year = value.substring(0, 4).toInt()
        val month = value.substring(4, 6).toInt()
        val day = value.substring(6, 8).toInt()
        val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        val days =
            when {
                month == FEBRUARY -> if (leap) DAYS_IN_FEBRUARY + 1 else DAYS_IN_FEBRUARY
                month in SHORT_MONTHS -> DAYS_IN_LONG_MONTH - 1
                else -> DAYS_IN_LONG_MONTH
            }
        return month in 1..12 && day in 1..days
    }

    @Suppress("MagicNumber") // HHMM の各部分の位置
    private fun isTime(value: String): Boolean =
        value.length == TIME_LENGTH &&
            value.all { it.isDigit() } &&
            value.substring(0, 2).toInt() <= MAX_HOUR &&
            value.substring(2, 4).toInt() <= MAX_MINUTE
}
