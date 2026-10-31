package io.eia.b2bgateway.domain

import io.eia.b2bgateway.domain.edifact.CharacterSet
import io.eia.b2bgateway.domain.edifact.EdifactLexer
import io.eia.b2bgateway.domain.validation.ElementError
import io.eia.b2bgateway.domain.validation.InterchangeCheck
import io.eia.b2bgateway.domain.validation.InterchangeResult
import io.eia.b2bgateway.domain.validation.PartyIdentification
import io.eia.b2bgateway.domain.validation.SegmentError
import io.eia.b2bgateway.domain.validation.ServiceError
import io.eia.b2bgateway.domain.validation.SyntaxErrorCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private fun check(text: String): InterchangeResult = InterchangeCheck.check(EdifactLexer.read(text), TestSpecs.orders)

private fun rejected(text: String): ServiceError = check(text).shouldBeInstanceOf<InterchangeResult.Rejected>().error

private fun checked(text: String): InterchangeResult.Checked = check(text).shouldBeInstanceOf<InterchangeResult.Checked>()

class InterchangeCheckSpec :
    FunSpec({
        test("正しい交換: 枠を受け付け、メッセージごとに受け付ける。UNB の値を応答の宛先として持つ") {
            val result = checked(interchange(VALID_BODY, VALID_BODY))

            result.messages.map { it.reference } shouldContainExactly listOf("M1", "M2")
            result.messages.all { it.accepted } shouldBe true
            result.messages[0].identifier shouldContainExactly listOf("ORDERS", "D", "96A", "UN")
            result.messages[0].segments shouldHaveSize VALID_BODY.size + 2
            with(result.header) {
                characterSet shouldBe CharacterSet.UNOA
                sender shouldBe PartyIdentification("4912345000019", "14")
                recipient shouldBe PartyIdentification("4900000000001", "14")
                controlReference shouldBe "ICR-1"
                test shouldBe false
            }
        }

        test("試験の交換(UNB の 0035 = 1)を見分ける") {
            checked(interchange(VALID_BODY, unb = "UNB+UNOA:3+S:14+R:14+261030:1200+ICR-1++++++1")).header.test shouldBe true
        }

        context("応答の宛先がわからない交換は Unreadable(応答を返さない)") {
            test("UNB がない・送り手か管理番号がない") {
                listOf(
                    "UNA:+.? 'UNH+1+ORDERS:D:96A:UN'",
                    "UNA:+.? 'UNB+UNOA:3++R:14+261030:1200+ICR'",
                    "UNA:+.? 'UNB+UNOA:3+S:14+R:14+261030:1200'",
                    "",
                ).forEach {
                    check(it) shouldBe InterchangeResult.Unreadable(ServiceError(SyntaxErrorCode.MISSING, "UNB"))
                }
            }
        }

        context("交換の誤り(UCI で拒否。ADR-0041 §2・§4)") {
            test("UNA がない → 13 / UNA、正しくない UNA → 20 / UNA(UNB は読めるので応答を返せる)") {
                check(interchange(VALID_BODY, una = null)).shouldBeInstanceOf<InterchangeResult.Rejected>().let {
                    it.error shouldBe ServiceError(SyntaxErrorCode.MISSING, "UNA")
                    it.header.controlReference shouldBe "ICR-1"
                }
                rejected(interchange(VALID_BODY, una = "UNA:+.?*'")) shouldBe ServiceError(SyntaxErrorCode.INVALID_SERVICE_CHARACTER, "UNA")
            }

            test("UNA の区切り文字が文字セットの外 → 20 / UNA") {
                rejected("UNA:+.? #" + interchange(VALID_BODY, una = null).replace('\'', '#')) shouldBe
                    ServiceError(SyntaxErrorCode.INVALID_SERVICE_CHARACTER, "UNA")
            }

            test("構文の版が 3 でない → 2、知らない文字セット → 12(応答は 1 バイトの文字を表せる UNOC で返す)") {
                rejected(interchange(VALID_BODY, unb = "UNB+UNOA:4+S:14+R:14+261030:1200+ICR-1")) shouldBe
                    ServiceError(SyntaxErrorCode.UNSUPPORTED_SYNTAX_VERSION, "UNB", 1, 2)
                check(
                    interchange(VALID_BODY, unb = "UNB+UNOD:3+S:14+R:14+261030:1200+ICR-1"),
                ).shouldBeInstanceOf<InterchangeResult.Rejected>().let {
                    it.error shouldBe ServiceError(SyntaxErrorCode.INVALID_VALUE, "UNB", 1, 1)
                    it.header.characterSet shouldBe CharacterSet.UNOC
                }
            }

            test("UNB のデータ要素の誤り(時刻)と字句の誤りは、UNB の位置で返す") {
                rejected(interchange(VALID_BODY, unb = "UNB+UNOA:3+S:14+R:14+261030:2460+ICR-1")) shouldBe
                    ServiceError(SyntaxErrorCode.INVALID_VALUE, "UNB", 4, 2)
                rejected(interchange(VALID_BODY, unb = "UNB+UNOA:3+S?X:14+R:14+261030:1200+ICR-1")) shouldBe
                    ServiceError(SyntaxErrorCode.INVALID_SERVICE_CHARACTER, "UNB", 2, 1)
            }

            test("機能グループ(UNG)は使わない → 15 / UNG") {
                rejected(interchange(VALID_BODY).replace("'UNH+M1", "'UNG+ORDERS'UNH+M1")) shouldBe
                    ServiceError(SyntaxErrorCode.NOT_SUPPORTED_IN_POSITION, "UNG")
            }

            test("UNZ がない・書きかけ → 13 / UNZ") {
                rejected(interchange(VALID_BODY).removeSuffix("UNZ+1+ICR-1'")) shouldBe ServiceError(SyntaxErrorCode.MISSING, "UNZ")
                rejected(interchange(VALID_BODY).removeSuffix("'")) shouldBe ServiceError(SyntaxErrorCode.MISSING, "UNZ")
            }

            test("UNZ の件数・管理番号が一致しない → 29 / 28") {
                rejected(interchange(VALID_BODY, unz = "UNZ+2+ICR-1")) shouldBe
                    ServiceError(SyntaxErrorCode.CONTROL_COUNT_MISMATCH, "UNZ", 1)
                rejected(interchange(VALID_BODY, unz = "UNZ+1+ICR-2")) shouldBe
                    ServiceError(SyntaxErrorCode.REFERENCES_DO_NOT_MATCH, "UNZ", 2)
                rejected(interchange(VALID_BODY, unz = "UNZ+X+ICR-1")) shouldBe
                    ServiceError(SyntaxErrorCode.INVALID_CHARACTER_TYPE, "UNZ", 1)
            }

            test("メッセージの外のセグメント → 33、メッセージがない → 32、多すぎる → 35 / UNH") {
                rejected(interchange(VALID_BODY).replace("'UNH+M1", "'BGM+220'UNH+M1")) shouldBe
                    ServiceError(SyntaxErrorCode.INVALID_OCCURRENCE_OUTSIDE_MESSAGE)
                rejected(interchange(unz = "UNZ+0+ICR-1")) shouldBe ServiceError(SyntaxErrorCode.LOWER_LEVEL_EMPTY)
                rejected(interchange(VALID_BODY, VALID_BODY, VALID_BODY, VALID_BODY)) shouldBe
                    ServiceError(SyntaxErrorCode.TOO_MANY_SEGMENT_REPETITIONS, "UNH")
            }
        }

        context("メッセージの枠の誤り(UCM で拒否。ほかのメッセージは受け付ける)") {
            test("UNH のメッセージの型が仕様と違う → 12 / UNH の位置") {
                val result = checked(interchange(VALID_BODY, VALID_BODY).replaceFirst("ORDERS:D:96A:UN", "ORDERS:D:01B:UN"))

                result.messages[0].header shouldBe ServiceError(SyntaxErrorCode.INVALID_VALUE, "UNH", 2, 3)
                result.messages[1].accepted shouldBe true
            }

            test("UNT がない → 13 / UNT(次の UNH の前までをそのメッセージとする)") {
                val result = checked(interchange(VALID_BODY, VALID_BODY).replaceFirst("'UNT+7+M1", ""))

                result.messages[0].header shouldBe ServiceError(SyntaxErrorCode.MISSING, "UNT")
                result.messages[0]
                    .segments
                    .last()
                    .tag shouldBe "NAD"
                result.messages[1].accepted shouldBe true
            }

            test("UNT の件数・参照番号が一致しない → 29 / 28") {
                checked(interchange(VALID_BODY).replace("UNT+7+M1", "UNT+6+M1")).messages[0].header shouldBe
                    ServiceError(SyntaxErrorCode.CONTROL_COUNT_MISMATCH, "UNT", 1)
                checked(interchange(VALID_BODY).replace("UNT+7+M1", "UNT+7+M9")).messages[0].header shouldBe
                    ServiceError(SyntaxErrorCode.REFERENCES_DO_NOT_MATCH, "UNT", 2)
            }
        }

        context("メッセージの中身の誤り(UCS・UCD で位置を示す)") {
            test("データ要素と並びの誤りを、UNH を 1 とした位置ごとにまとめる") {
                val body = listOf("BGM+221+PO-1+9", "DTM+137:20261301:102", "NAD+BY+4912345000019", "LOC+A1", "NAD+SU+4900000000001")
                val message = checked(interchange(body)).messages.single()

                message.accepted shouldBe false
                message.header shouldBe null
                message.errors shouldContainExactly
                    listOf(
                        SegmentError(2, null, listOf(ElementError(SyntaxErrorCode.INVALID_VALUE, 1, 1))),
                        SegmentError(3, null, listOf(ElementError(SyntaxErrorCode.INVALID_VALUE, 1, 2))),
                        SegmentError(5, null, listOf(ElementError(SyntaxErrorCode.INVALID_CHARACTER_TYPE, 1))),
                    )
            }

            test("文字セットの外の文字(UNOA の英小文字)→ 21 の位置。UNOB なら受け付ける") {
                val body = VALID_BODY.map { it.replace("PO-1", "po-1") }

                checked(interchange(body)).messages.single().errors shouldContainExactly
                    listOf(SegmentError(2, null, listOf(ElementError(SyntaxErrorCode.INVALID_CHARACTERS, 2))))
                checked(interchange(body, unb = "UNB+UNOB:3+S:14+R:14+261030:1200+ICR-1")).messages.single().accepted shouldBe true
            }

            test("字句の誤り: 解放文字の誤りはデータ要素の位置、タグの誤りはセグメントの誤り(並びの外として 15 も)") {
                val errors = checked(interchange(listOf("BGM+220+P?O+9") + VALID_BODY.drop(1) + "bgm+1")).messages.single().errors

                errors shouldContainExactly
                    listOf(
                        SegmentError(2, null, listOf(ElementError(SyntaxErrorCode.INVALID_SERVICE_CHARACTER, 2, 1))),
                        SegmentError(7, SyntaxErrorCode.INVALID_VALUE),
                    )
            }

            test("並びの外のセグメントが続いても、最初の 1 つで並びの検査をやめる") {
                val body = VALID_BODY + List(InterchangeCheck.MAX_SEGMENT_ERRORS + 10) { "FTX+X" }
                val message = checked(interchange(body)).messages.single()

                message.accepted shouldBe false
                message.errors shouldContainExactly listOf(SegmentError(7, SyntaxErrorCode.NOT_SUPPORTED_IN_POSITION))
                checked(interchange(VALID_BODY))
                    .messages
                    .single()
                    .errors
                    .shouldBeEmpty()
            }
        }
    })
