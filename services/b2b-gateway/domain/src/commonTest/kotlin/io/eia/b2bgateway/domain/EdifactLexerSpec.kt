package io.eia.b2bgateway.domain

import io.eia.b2bgateway.domain.edifact.CharacterSet
import io.eia.b2bgateway.domain.edifact.EdifactLexer
import io.eia.b2bgateway.domain.edifact.EdifactWriter
import io.eia.b2bgateway.domain.edifact.Latin1
import io.eia.b2bgateway.domain.edifact.LexIssue
import io.eia.b2bgateway.domain.edifact.LexIssueKind
import io.eia.b2bgateway.domain.edifact.Segment
import io.eia.b2bgateway.domain.edifact.ServiceCharacters
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class EdifactLexerSpec :
    FunSpec({
        context("UNA と区切り文字(ADR-0041 §2)") {
            test("UNA の 6 文字を区切り文字にする(既定と違う文字でも読む)") {
                val lexed = EdifactLexer.read("UNA|*,! #UNB*UNOC|3*X#UNZ*0*1#")

                lexed.serviceCharacters shouldBe ServiceCharacters('|', '*', ',', '!', ' ', '#')
                lexed.una shouldBe "UNA|*,! #"
                lexed.segments.map { it.tag } shouldContainExactly listOf("UNB", "UNZ")
                lexed.segments[0].elements shouldContainExactly listOf(listOf("UNOC", "3"), listOf("X"))
                lexed.issues.shouldBeEmpty()
                lexed.terminated shouldBe true
            }

            test("UNA がなければ誤りとして残し、応答の宛先を読むために既定の区切り文字で読む") {
                val lexed = EdifactLexer.read("UNB+UNOA:3+S:14+R:14+261030:1200+ICR'")

                lexed.una shouldBe null
                lexed.serviceCharacters shouldBe ServiceCharacters.FALLBACK
                lexed.issues shouldContainExactly listOf(LexIssue(null, null, null, LexIssueKind.UNA_MISSING))
                lexed.segments.single().value(2, 1) shouldBe "S"
            }

            test("予約の位置が空白でない・区切り文字が重複・英数字の UNA は誤り") {
                listOf("UNA:+.?*'", "UNA::.? '", "UNA:+A? '").forEach {
                    EdifactLexer
                        .read("${it}UNB+UNOA:3'")
                        .issues
                        .first()
                        .kind shouldBe LexIssueKind.UNA_INVALID
                }
            }
        }

        context("解放文字と改行") {
            test("解放文字の直後の区切り・終端・解放文字は値の文字として読む") {
                val segment = EdifactLexer.read("UNA:+.? 'FTX+A?+B?:C??D?'E'").segments.single()

                segment.elements shouldContainExactly listOf(listOf("A+B:C?D'E"))
            }

            test("区切り・解放文字・終端でない文字の前の解放文字は、その位置の誤り") {
                val lexed = EdifactLexer.read("UNA:+.? 'UNB+X'BGM+220:?A'")

                lexed.issues shouldContainExactly listOf(LexIssue(1, 1, 2, LexIssueKind.RELEASE_NOT_BEFORE_SERVICE_CHARACTER))
                lexed.segments[1].elements shouldContainExactly listOf(listOf("220", "A"))
            }

            test("セグメントの終端の直後の CR・LF は読み飛ばす。値の中の改行は値に残す(文字セットの検査で誤りになる)") {
                val lexed = EdifactLexer.read("UNA:+.? 'UNB+X'\r\nBGM+2\n20'\nUNZ+1'\r\n")

                lexed.segments.map { it.tag } shouldContainExactly listOf("UNB", "BGM", "UNZ")
                lexed.segments[1].value(1) shouldBe "2\n20"
                lexed.terminated shouldBe true
            }
        }

        context("書きかけ・壊れた交換") {
            test("最後のセグメントが終端で終わっていなければ terminated = false") {
                EdifactLexer.read("UNA:+.? 'UNB+X'UNZ+1").terminated shouldBe false
                EdifactLexer.read("UNA:+.? 'UNB+X'UNZ+1?").terminated shouldBe false
            }

            test("タグが英大文字 3 文字でないセグメントは誤り") {
                EdifactLexer.read("UNA:+.? 'UNB+X'bgm+1'B:X+1'").issues shouldContainExactly
                    listOf(LexIssue(1, null, null, LexIssueKind.INVALID_TAG), LexIssue(2, null, null, LexIssueKind.INVALID_TAG))
            }
        }

        context("書き出し") {
            test("値の中の区切り・解放文字・終端の前に解放文字を置き、末尾の空の構成要素とデータ要素を省く") {
                val text =
                    EdifactWriter.write(
                        ServiceCharacters.FALLBACK,
                        listOf(
                            Segment("FTX", listOf(listOf("A+B", ""), listOf("C:D'E?"), listOf("", ""), listOf(""))),
                            Segment.of("UNZ", listOf("1")),
                        ),
                    )

                text shouldBe "UNA:+.? 'FTX+A?+B+C?:D?'E??'UNZ+1'"
                EdifactLexer.read(text).segments[0].elements shouldContainExactly listOf(listOf("A+B"), listOf("C:D'E?"))
            }

            test("Latin1 は 1 バイトを 1 文字に対応させる。0xFF を超える文字は書けない") {
                val bytes = byteArrayOf(0x41, 0xE9.toByte(), 0xFF.toByte())

                Latin1.decode(bytes) shouldBe "Aéÿ"
                Latin1.encode("Aéÿ").toList() shouldContainExactly bytes.toList()
                shouldThrow<IllegalArgumentException> { Latin1.encode("あ") }
            }
        }

        context("文字セット(ADR-0041 §3)") {
            test("UNOA は英大文字・数字・空白と、割り当てが変わらない位置の記号だけ") {
                "AZ09 .,-()/='+:?!\"%&*;<>_".all(CharacterSet.UNOA::allows) shouldBe true
                "az#$@[\\]^`{|}~\n\té".none(CharacterSet.UNOA::allows) shouldBe true
            }

            test("UNOB は UNOA に英小文字を足したもの") {
                "AZaz09 .?_".all(CharacterSet.UNOB::allows) shouldBe true
                "#@{é\r".none(CharacterSet.UNOB::allows) shouldBe true
            }

            test("UNOC は ISO 8859-1 の図形文字(制御文字は入らない)") {
                "Aaé#@{~ ÿ".all(CharacterSet.UNOC::allows) shouldBe true
                "\r\n\u007F\u0080\u009F".none(CharacterSet.UNOC::allows) shouldBe true
            }

            test("知らない文字セットは null") {
                CharacterSet.of("UNOC") shouldBe CharacterSet.UNOC
                CharacterSet.of("UNOY") shouldBe null
            }
        }
    })
