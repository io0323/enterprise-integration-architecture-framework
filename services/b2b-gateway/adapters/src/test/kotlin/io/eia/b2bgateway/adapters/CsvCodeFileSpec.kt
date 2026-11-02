package io.eia.b2bgateway.adapters

import io.eia.b2bgateway.adapters.codes.CsvCodeFile
import io.eia.b2bgateway.application.port.inbound.CodeTableImport
import io.eia.b2bgateway.application.port.inbound.InvalidCodeTable
import io.eia.b2bgateway.domain.codes.CodeKind
import io.eia.b2bgateway.domain.codes.CodeRow
import io.eia.b2bgateway.domain.codes.CodeTableViolation
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private fun read(bytes: ByteArray): Result<CodeTableImport, DomainError> = CsvCodeFile.read("partner01", CodeKind.ITEMS, bytes, "ops-1")

private fun read(text: String): CodeTableImport = read(text.encodeToByteArray()).shouldBeInstanceOf<Result.Ok<CodeTableImport>>().value

private fun violation(bytes: ByteArray): CodeTableViolation =
    (read(bytes) as Result.Err)
        .error
        .shouldBeInstanceOf<InvalidCodeTable>()
        .violations
        .single()

class CsvCodeFileSpec :
    FunSpec({
        test("見出しと行を読む。BOM・CRLF・引用(カンマ・改行・\"\")を扱い、空行は飛ばして、行の番号はファイルの行のまま") {
            val file =
                read(
                    "\uFEFFcode_type,code,product_id,sku\r\n" +
                        "gtin,4901234567900,prod-2,SKU-2\r\n\r\n" +
                        "\"buyer_part_number\",\"A,\"\"B\"\"\nC\",p,s\n",
                )

            file.header shouldContainExactly listOf("code_type", "code", "product_id", "sku")
            file.rows shouldContainExactly
                listOf(
                    CodeRow(2, listOf("gtin", "4901234567900", "prod-2", "SKU-2")),
                    CodeRow(4, listOf("buyer_part_number", "A,\"B\"\nC", "p", "s")),
                )
            file.partnerId shouldBe "partner01"
            file.operator shouldBe "ops-1"
            file.sha256.length shouldBe 64
        }

        test("最後の行に改行がなくても読む。空の値も列として数える") {
            read("a,b\nx,").rows shouldContainExactly listOf(CodeRow(2, listOf("x", "")))
        }

        test("読めないファイル: 引用が閉じていない・UTF-8 でない・空") {
            violation("a,b\nx,\"y\nz".encodeToByteArray()) shouldBe CodeTableViolation(2, null, "引用(\")が閉じていません")
            violation(byteArrayOf(0x61, 0xC3.toByte(), 0x28)) shouldBe CodeTableViolation(1, null, "UTF-8 として読めません")
            violation(ByteArray(0)) shouldBe CodeTableViolation(1, null, "見出しがありません")
        }
    })
