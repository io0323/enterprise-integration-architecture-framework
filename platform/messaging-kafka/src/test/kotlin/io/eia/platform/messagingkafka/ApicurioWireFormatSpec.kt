package io.eia.platform.messagingkafka

import io.eia.platform.schemaregistry.ContentId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

class ApicurioWireFormatSpec :
    FunSpec({
        test("先頭は 0x00、続く 4 バイトがビッグエンディアンの contentId、残りが Avro のバイナリ") {
            ApicurioWireFormat.frame(ContentId(0x01020304), byteArrayOf(9, 8)).toList() shouldBe
                listOf<Byte>(0, 1, 2, 3, 4, 9, 8)
        }

        test("frame したものを parse すると元に戻る") {
            checkAll(Arb.long(0L..Int.MAX_VALUE), Arb.byteArray(Arb.int(0..64), Arb.byte())) { id, body ->
                val framed = ApicurioWireFormat.parse(ApicurioWireFormat.frame(ContentId(id), body)).ok()
                framed.contentId shouldBe ContentId(id)
                framed.avroBinary.toList() shouldBe body.toList()
            }
        }

        test("短すぎる・先頭が 0x00 でない・負の ID は MalformedEventPayload") {
            listOf(byteArrayOf(), byteArrayOf(0, 0, 0, 1), byteArrayOf(1, 0, 0, 0, 1), byteArrayOf(0, -1, -1, -1, -1)).forEach {
                ApicurioWireFormat.parse(it).err().shouldBeInstanceOf<MalformedEventPayload>()
            }
        }
    })
