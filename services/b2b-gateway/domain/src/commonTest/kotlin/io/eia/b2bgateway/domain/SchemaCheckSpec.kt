package io.eia.b2bgateway.domain

import io.eia.b2bgateway.domain.TestSpecs.composite
import io.eia.b2bgateway.domain.TestSpecs.simple
import io.eia.b2bgateway.domain.edifact.CharacterSet
import io.eia.b2bgateway.domain.edifact.Segment
import io.eia.b2bgateway.domain.spec.DataFormat
import io.eia.b2bgateway.domain.spec.SegmentDefinition
import io.eia.b2bgateway.domain.spec.Semantic
import io.eia.b2bgateway.domain.spec.Status
import io.eia.b2bgateway.domain.validation.ElementCheck
import io.eia.b2bgateway.domain.validation.ElementError
import io.eia.b2bgateway.domain.validation.SegmentError
import io.eia.b2bgateway.domain.validation.StructureCheck
import io.eia.b2bgateway.domain.validation.SyntaxErrorCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

@Suppress("LongParameterList") // 値の検査の条件を、既定値つきの名前付きの引数で並べる
private fun value(
    format: String,
    value: String,
    status: Status = Status.MANDATORY,
    codes: Set<String> = emptySet(),
    semantic: Semantic? = null,
    characterSet: CharacterSet = CharacterSet.UNOA,
    decimal: Char = '.',
): SyntaxErrorCode? = ElementCheck.checkValue(simple("9999", status, format, codes, semantic), value, characterSet, decimal)

/** 本文のセグメント(タグと最初の値だけ)。 */
private fun body(vararg segments: String): List<Segment> =
    segments.map { text ->
        val parts = text.split('+')
        Segment(parts[0], parts.drop(1).map { listOf(it) })
    }

class SchemaCheckSpec :
    FunSpec({
        context("データ要素の値(ADR-0041 §4)") {
            test("状態: M の空は 13、X に値があれば 15、C の空はよい") {
                value("an..3", "") shouldBe SyntaxErrorCode.MISSING
                value("an..3", "A", Status.NOT_USED) shouldBe SyntaxErrorCode.NOT_SUPPORTED_IN_POSITION
                value("an..3", "", Status.CONDITIONAL) shouldBe null
            }

            test("長さ: 最大を超えれば 39、固定より短ければ 40") {
                value("an..3", "ABCD") shouldBe SyntaxErrorCode.DATA_ELEMENT_TOO_LONG
                value("n6", "26103") shouldBe SyntaxErrorCode.DATA_ELEMENT_TOO_SHORT
                value("n6", "2610301") shouldBe SyntaxErrorCode.DATA_ELEMENT_TOO_LONG
                value("an..3", "ABC") shouldBe null
            }

            test("種類: a に数字、n に英字は 37") {
                value("a..3", "A1") shouldBe SyntaxErrorCode.INVALID_CHARACTER_TYPE
                value("n..3", "1A") shouldBe SyntaxErrorCode.INVALID_CHARACTER_TYPE
                value("n..3", "-") shouldBe SyntaxErrorCode.INVALID_CHARACTER_TYPE
            }

            test("数値: 符号と小数点は長さに数えない。指定と違う小数点・2 つの小数点・末尾の小数点は 19、小数点の前に数字がなければ 38") {
                value("n..3", "-1.25") shouldBe null
                value("n..3", "1,25", decimal = ',') shouldBe null
                value("n..3", "1,25") shouldBe SyntaxErrorCode.INVALID_DECIMAL_NOTATION
                value("n..3", "1.25", decimal = ',') shouldBe SyntaxErrorCode.INVALID_DECIMAL_NOTATION
                value("n..4", "1.2.5") shouldBe SyntaxErrorCode.INVALID_DECIMAL_NOTATION
                value("n..3", "12.") shouldBe SyntaxErrorCode.INVALID_DECIMAL_NOTATION
                value("n..3", ".5") shouldBe SyntaxErrorCode.MISSING_DIGIT_BEFORE_DECIMAL
                value("n..3", "1.234") shouldBe SyntaxErrorCode.DATA_ELEMENT_TOO_LONG
            }

            test("コード値にない値・実在しない日付・時刻は 12") {
                value("an..3", "221", codes = setOf("220")) shouldBe SyntaxErrorCode.INVALID_VALUE
                listOf("20261301", "20260230", "20250229", "2026010A").forEach {
                    value("an..8", it, semantic = Semantic.DATE) shouldBe
                        SyntaxErrorCode.INVALID_VALUE
                }
                listOf("20240229", "21000228", "20260430").forEach { value("n8", it, semantic = Semantic.DATE) shouldBe null }
                value("n4", "2400", semantic = Semantic.TIME) shouldBe SyntaxErrorCode.INVALID_VALUE
                value("an4", "1260", semantic = Semantic.TIME) shouldBe SyntaxErrorCode.INVALID_VALUE
                value("n4", "2359", semantic = Semantic.TIME) shouldBe null
            }

            test("文字セットの外の文字は 21(UNOA の英小文字、UNOC の制御文字)") {
                value("an..9", "abc") shouldBe SyntaxErrorCode.INVALID_CHARACTERS
                value("an..9", "abc", characterSet = CharacterSet.UNOB) shouldBe null
                value("an..9", "Café", characterSet = CharacterSet.UNOC) shouldBe null
                value("an..9", "A\nB", characterSet = CharacterSet.UNOC) shouldBe SyntaxErrorCode.INVALID_CHARACTERS
            }

            test("形式の表現を読む") {
                DataFormat.parse("an..35")?.let { (it.length to it.upTo) } shouldBe (35 to true)
                DataFormat.parse("n6")?.upTo shouldBe false
                DataFormat.parse("x..3") shouldBe null
            }
        }

        context("セグメントのデータ要素") {
            val definition =
                SegmentDefinition(
                    "NAD",
                    listOf(
                        simple("3035", Status.MANDATORY, "an..3", setOf("BY")),
                        composite(
                            "C082",
                            Status.MANDATORY,
                            simple("3039", Status.MANDATORY, "an..35"),
                            simple("1131", Status.NOT_USED, "an..3"),
                        ),
                        composite("C058", Status.NOT_USED, simple("3124", Status.NOT_USED, "an..35")),
                        composite("C080", Status.CONDITIONAL, simple("3036", Status.MANDATORY, "an..35")),
                    ),
                )

            fun check(vararg elements: List<String>) =
                ElementCheck.check(definition, Segment("NAD", elements.toList()), CharacterSet.UNOA, '.')

            test("正しい値なら誤りなし(任意の複合データ要素は省いてよい)") {
                check(listOf("BY"), listOf("4912345000019")).shouldBeEmpty()
            }

            test("位置はタグの次のデータ要素を 1、構成要素も 1 から数える") {
                check(listOf("SU"), listOf("4912345000019", "9")) shouldContainExactly
                    listOf(ElementError(SyntaxErrorCode.INVALID_VALUE, 1), ElementError(SyntaxErrorCode.NOT_SUPPORTED_IN_POSITION, 2, 2))
            }

            test("必須の複合データ要素がない → 13、使わない複合データ要素に値 → 15") {
                check(listOf("BY")) shouldContainExactly listOf(ElementError(SyntaxErrorCode.MISSING, 2))
                check(listOf("BY"), listOf("G"), listOf("X")) shouldContainExactly
                    listOf(ElementError(SyntaxErrorCode.NOT_SUPPORTED_IN_POSITION, 3))
            }

            test("データ要素・構成要素の数が定義より多ければ 16") {
                check(listOf("BY"), listOf("G"), listOf(), listOf(), listOf("Z")) shouldContainExactly
                    listOf(ElementError(SyntaxErrorCode.TOO_MANY_CONSTITUENTS, 5))
                check(listOf("BY", "Z"), listOf("G", "", "Z")) shouldContainExactly
                    listOf(
                        ElementError(SyntaxErrorCode.TOO_MANY_CONSTITUENTS, 1, 2),
                        ElementError(SyntaxErrorCode.TOO_MANY_CONSTITUENTS, 2, 3),
                    )
            }
        }

        context("並びと回数(ADR-0041 §4)") {
            val structure = TestSpecs.orders.structure

            fun check(vararg segments: String) = StructureCheck.check(structure, body(*segments))

            test("正しい並び(グループの繰り返し・任意のセグメントの省略)なら誤りなし") {
                check("BGM+220", "DTM+137", "DTM+2", "NAD+BY", "LOC+A", "QTY+21", "NAD+SU").shouldBeEmpty()
            }

            test("必須のセグメントがない → 13(位置はそこで見つかったセグメント。末尾なら UNT の位置)") {
                check("DTM+137", "NAD+BY") shouldContainExactly listOf(SegmentError(2, SyntaxErrorCode.MISSING))
                check("BGM+220", "DTM+137") shouldContainExactly listOf(SegmentError(4, SyntaxErrorCode.MISSING))
            }

            test("セグメントの回数の超過は 35、グループの回数の超過は 36(超えた回の位置)") {
                check("BGM+220", "BGM+220", "DTM+137", "NAD+BY") shouldContainExactly
                    listOf(SegmentError(3, SyntaxErrorCode.TOO_MANY_SEGMENT_REPETITIONS))
                check("BGM+220", "DTM+137", "NAD+BY", "NAD+SU", "NAD+SU") shouldContainExactly
                    listOf(
                        SegmentError(6, SyntaxErrorCode.TOO_MANY_GROUP_REPETITIONS),
                        SegmentError(6, SyntaxErrorCode.TOO_MANY_GROUP_REPETITIONS),
                    )
            }

            test("修飾子: 必須の修飾子がない → 13(最初の出現の位置)、修飾子ごとの回数の超過 → 35") {
                check("BGM+220", "DTM+2", "NAD+BY") shouldContainExactly listOf(SegmentError(3, SyntaxErrorCode.MISSING))
                check("BGM+220", "DTM+137", "DTM+137", "NAD+BY") shouldContainExactly
                    listOf(SegmentError(4, SyntaxErrorCode.TOO_MANY_SEGMENT_REPETITIONS))
                check("BGM+220", "DTM+137", "NAD+SU") shouldContainExactly listOf(SegmentError(4, SyntaxErrorCode.MISSING))
            }

            test("並びのどこにも当てはまらないセグメント → 15(そこで検査をやめる)") {
                check("BGM+220", "DTM+137", "NAD+BY", "FTX+X", "NAD+SU") shouldContainExactly
                    listOf(SegmentError(5, SyntaxErrorCode.NOT_SUPPORTED_IN_POSITION))
                check("BGM+220", "NAD+BY", "DTM+137") shouldContainExactly
                    listOf(SegmentError(3, SyntaxErrorCode.MISSING), SegmentError(4, SyntaxErrorCode.NOT_SUPPORTED_IN_POSITION))
            }
        }
    })
