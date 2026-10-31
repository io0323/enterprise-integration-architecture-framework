package io.eia.b2bgateway.domain

import io.eia.b2bgateway.domain.ack.Contrl
import io.eia.b2bgateway.domain.ack.ResponseEnvelope
import io.eia.b2bgateway.domain.ack.UtcStamp
import io.eia.b2bgateway.domain.edifact.EdifactLexer
import io.eia.b2bgateway.domain.edifact.Latin1
import io.eia.b2bgateway.domain.validation.InterchangeCheck
import io.eia.b2bgateway.domain.validation.InterchangeResult
import io.eia.b2bgateway.domain.validation.ServiceError
import io.eia.b2bgateway.domain.validation.SyntaxErrorCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant

private val ENVELOPE = ResponseEnvelope("R-7", "1", Instant.parse("2026-10-31T09:05:00Z"))

private fun contrl(text: String): String {
    val result = InterchangeCheck.check(EdifactLexer.read(text), TestSpecs.orders)
    val header =
        when (result) {
            is InterchangeResult.Rejected -> result.header
            is InterchangeResult.Checked -> result.header
            is InterchangeResult.Unreadable -> error("宛先がない")
        }
    return Latin1.decode(Contrl.write(header, checkNotNull(Contrl.build(TestSpecs.contrl, result, ENVELOPE))))
}

class ContrlSpec :
    FunSpec({
        test("受け付けた交換: UCI = 7 とメッセージごとの UCM = 7。宛先は受け取った交換の送り手、日時は UTC") {
            contrl(interchange(VALID_BODY, VALID_BODY)) shouldBe
                "UNA:+.? 'UNB+UNOA:3+4900000000001:14+4912345000019:14+261031:0905+R-7'UNH+1+CONTRL:D:3:UN'" +
                "UCI+ICR-1+4912345000019:14+4900000000001:14+7'UCM+M1+ORDERS:D:96A:UN+7'UCM+M2+ORDERS:D:96A:UN+7'UNT+5+1'UNZ+1+R-7'"
        }

        test("交換の誤り: UCI = 4 と 0085・0013・S011 だけ(UCM は付けない)") {
            contrl(interchange(VALID_BODY, unb = "UNB+UNOA:4+S:14+R:14+261030:1200+ICR-1")) shouldBe
                "UNA:+.? 'UNB+UNOA:3+R:14+S:14+261031:0905+R-7'UNH+1+CONTRL:D:3:UN'UCI+ICR-1+S:14+R:14+4+2+UNB+1:2'UNT+3+1'UNZ+1+R-7'"
        }

        test("メッセージの誤り: UCM = 4 と、UCS(位置)・UCD(データ要素の位置)。受け付けたメッセージは UCM = 7") {
            val bad = listOf("BGM+221+PO-1+9", "DTM+2:20261101:102") + VALID_BODY.drop(2)

            contrl(interchange(bad, VALID_BODY)) shouldBe
                "UNA:+.? 'UNB+UNOA:3+4900000000001:14+4912345000019:14+261031:0905+R-7'UNH+1+CONTRL:D:3:UN'" +
                "UCI+ICR-1+4912345000019:14+4900000000001:14+7'" +
                "UCM+M1+ORDERS:D:96A:UN+4'UCS+2'UCD+12+1:1'UCS+3+13'" +
                "UCM+M2+ORDERS:D:96A:UN+7'UNT+8+1'UNZ+1+R-7'"
        }

        test("メッセージの枠の誤りは UCM の 0085・0013・S011") {
            contrl(interchange(VALID_BODY).replace("UNT+7+M1", "UNT+6+M1")) shouldBe
                "UNA:+.? 'UNB+UNOA:3+4900000000001:14+4912345000019:14+261031:0905+R-7'UNH+1+CONTRL:D:3:UN'" +
                "UCI+ICR-1+4912345000019:14+4900000000001:14+7'UCM+M1+ORDERS:D:96A:UN+4+29+UNT+1'UNT+4+1'UNZ+1+R-7'"
        }

        test("試験の交換には UNB の 0035 = 1 を付け、受け取った区切り文字で書く(値の中の区切り文字は解放文字で守る)") {
            val text =
                "UNA|*,! #" +
                    interchange(VALID_BODY, una = null, unb = "UNB+UNOC:3+SENDER:14+R:14+261030:1200+ICR-1++++++1")
                        .replace('+', '*')
                        .replace(':', '|')
                        .replace('\'', '#')
                        .replace("SENDER", "S'!*1") // 送り手の値は S'*1(* は区切り文字なので解放文字で守る。' はこの交換ではただの文字)。| と # は UNOC にだけある文字

            contrl(text) shouldBe
                "UNA|*,! #UNB*UNOC|3*R|14*S'!*1|14*261031|0905*R-7******1#UNH*1*CONTRL|D|3|UN#UCI*ICR-1*S'!*1|14*R|14*7#" +
                "UCM*M1*ORDERS|D|96A|UN*7#UNT*4*1#UNZ*1*R-7#"
        }

        test("UNB を読めない交換には応答を作らない") {
            Contrl.build(TestSpecs.contrl, InterchangeResult.Unreadable(ServiceError(SyntaxErrorCode.MISSING, "UNB")), ENVELOPE) shouldBe
                null
        }

        test("作った CONTRL は、仕様の構文の検査をそのまま通る(区切り文字・件数・管理番号)") {
            val lexed = EdifactLexer.read(contrl(interchange(VALID_BODY)))

            lexed.issues shouldContainExactly emptyList()
            lexed.segments.last().elements shouldContainExactly listOf(listOf("1"), listOf("R-7"))
            // 交換の枠(件数・管理番号)は正しい。テスト用の仕様の UNH は ORDERS だけを許すので、メッセージの枠は誤りになる
            // (実際の CONTRL の仕様での検査は adapters のテスト)
            InterchangeCheck
                .check(
                    lexed,
                    TestSpecs.contrl,
                ).shouldBeInstanceOf<InterchangeResult.Checked>()
                .messages
                .single()
                .header shouldBe
                ServiceError(SyntaxErrorCode.INVALID_VALUE, "UNH", 2, 1)
        }

        test("UTC の日時(YYMMDD・HHMM)。うるう年・世紀・1970 年より前") {
            UtcStamp.of(Instant.parse("2024-02-29T23:59:59Z")) shouldBe UtcStamp("240229", "2359")
            UtcStamp.of(Instant.parse("2000-03-01T00:00:00Z")) shouldBe UtcStamp("000301", "0000")
            UtcStamp.of(Instant.parse("1969-12-31T23:00:00Z")) shouldBe UtcStamp("691231", "2300")
            UtcStamp.of(Instant.parse("2100-02-28T12:30:00Z")) shouldBe UtcStamp("000228", "1230")
        }
    })
