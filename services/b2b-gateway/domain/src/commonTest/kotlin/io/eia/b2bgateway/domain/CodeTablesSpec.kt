package io.eia.b2bgateway.domain

import io.eia.b2bgateway.domain.codes.CodeKind
import io.eia.b2bgateway.domain.codes.CodeRow
import io.eia.b2bgateway.domain.codes.CodeTable
import io.eia.b2bgateway.domain.codes.CodeTableCheck
import io.eia.b2bgateway.domain.codes.CodeTableViolation
import io.eia.b2bgateway.domain.codes.CodeTables
import io.eia.b2bgateway.domain.order.InternalCustomer
import io.eia.b2bgateway.domain.order.InternalItem
import io.eia.b2bgateway.domain.order.ItemCode
import io.eia.b2bgateway.domain.order.ShippingAddress
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private fun rows(vararg values: List<String>): List<CodeRow> = values.mapIndexed { index, row -> CodeRow(index + 2, row) }

private fun violations(
    kind: CodeKind,
    vararg values: List<String>,
): List<CodeTableViolation> = CodeTables.check(kind, kind.columns, rows(*values)).shouldBeInstanceOf<CodeTableCheck.Invalid>().violations

class CodeTablesSpec :
    FunSpec({
        test("買い手: GLN → 顧客と既定の届け先。region と line2 は空なら null") {
            val check =
                CodeTables.check(
                    CodeKind.BUYERS,
                    CodeKind.BUYERS.columns,
                    rows(listOf("4912345000019", "cust-1", "JP", "100-0001", "", "Chiyoda", " 1-1 ", "")),
                )

            check shouldBe
                CodeTableCheck.Valid(
                    CodeTable.Buyers(
                        mapOf(
                            "4912345000019" to InternalCustomer("cust-1", ShippingAddress("JP", "100-0001", null, "Chiyoda", "1-1", null)),
                        ),
                    ),
                )
        }

        test("届け先と商品(GTIN と取引先の商品コード)") {
            val parties =
                CodeTables.check(
                    CodeKind.DELIVERY_PARTIES,
                    CodeKind.DELIVERY_PARTIES.columns,
                    rows(listOf("4912345000026", "JP", "530-0001", "Osaka", "Kita", "2-2", "Bldg 3")),
                )
            val items =
                CodeTables.check(
                    CodeKind.ITEMS,
                    CodeKind.ITEMS.columns,
                    rows(listOf("buyer_part_number", "ABC-123", "prod-1", "SKU-1"), listOf("gtin", "4901234567900", "prod-2", "SKU-2")),
                )

            parties.shouldBeInstanceOf<CodeTableCheck.Valid>().table shouldBe
                CodeTable.DeliveryParties(mapOf("4912345000026" to ShippingAddress("JP", "530-0001", "Osaka", "Kita", "2-2", "Bldg 3")))
            items.shouldBeInstanceOf<CodeTableCheck.Valid>().table shouldBe
                CodeTable.Items(
                    mapOf(
                        ItemCode.BuyerPartNumber("ABC-123") to InternalItem("prod-1", "SKU-1"),
                        ItemCode.Gtin("4901234567900") to InternalItem("prod-2", "SKU-2"),
                    ),
                )
        }

        test("見出しが違う・行がないファイルは、入れ替えない") {
            CodeTables.check(CodeKind.ITEMS, listOf("code", "code_type", "product_id", "sku"), rows()) shouldBe
                CodeTableCheck.Invalid(listOf(CodeTableViolation(1, null, "見出しは code_type,code,product_id,sku にしてください")))
            CodeTables.check(CodeKind.ITEMS, CodeKind.ITEMS.columns, rows()) shouldBe
                CodeTableCheck.Invalid(listOf(CodeTableViolation(1, null, "行がありません(空の表には入れ替えません)")))
        }

        test("行の誤り: 形式・空・長さ・列の数・重複を、行の番号と列の名前で返す(値は入れない)") {
            violations(
                CodeKind.BUYERS,
                listOf("491234500001", "", "jp", "100-0001", "", "Chiyoda", "1-1", ""),
                listOf("4912345000019", "cust-1", "JP", "100-0001", "", "Chiyoda", "x".repeat(71), ""),
                listOf("4912345000019", "cust-1"),
                listOf("4912345000019", "cust-1", "JP", "100-0001", "", "Chiyoda", "1-1", ""),
                listOf("4912345000019", "cust-2", "JP", "100-0001", "", "Chiyoda", "1-1", ""),
            ) shouldContainExactly
                listOf(
                    CodeTableViolation(2, "gln", "GLN は 13 桁の数字にしてください"),
                    CodeTableViolation(2, "customer_id", "値がありません"),
                    CodeTableViolation(2, "country_code", "国は ISO 3166-1 の 2 文字の大文字にしてください"),
                    CodeTableViolation(3, "line1", "70 文字までにしてください"),
                    CodeTableViolation(4, null, "列の数が見出しと違います"),
                    CodeTableViolation(6, null, "同じコードの行が前にあります"),
                )
            violations(
                CodeKind.ITEMS,
                listOf("ean", "4901234567900", "prod-2", "SKU-2"),
                listOf("gtin", "490123456790", "prod-2", "SKU-2"),
                listOf("gtin", "4901234567", "prod-2", "SKU-2"),
                listOf("buyer_part_number", "x".repeat(36), "prod-3", "SKU-3"),
            ).map { it.row to it.column } shouldContainExactly listOf(2 to "code_type", 4 to "code", 5 to "code")
        }

        test("種類の名前(コマンドの引数)") {
            CodeKind.of("delivery-parties") shouldBe CodeKind.DELIVERY_PARTIES
            CodeKind.of("unknown") shouldBe null
        }
    })
