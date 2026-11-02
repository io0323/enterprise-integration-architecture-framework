package io.eia.b2bgateway.application

import io.eia.b2bgateway.application.port.inbound.CodeTableImport
import io.eia.b2bgateway.application.port.inbound.ImportedCodeTable
import io.eia.b2bgateway.application.port.inbound.InvalidCodeTable
import io.eia.b2bgateway.application.port.inbound.UnknownPartner
import io.eia.b2bgateway.application.port.outbound.CodeTableSource
import io.eia.b2bgateway.application.port.outbound.CodeTableStore
import io.eia.b2bgateway.application.port.outbound.PartnerAgreements
import io.eia.b2bgateway.application.usecase.ImportCodeTableService
import io.eia.b2bgateway.domain.agreement.PartnerAgreement
import io.eia.b2bgateway.domain.codes.CodeKind
import io.eia.b2bgateway.domain.codes.CodeRow
import io.eia.b2bgateway.domain.codes.CodeTable
import io.eia.b2bgateway.domain.codes.CodeTableViolation
import io.eia.b2bgateway.domain.order.InternalItem
import io.eia.b2bgateway.domain.order.ItemCode
import io.eia.b2bgateway.domain.validation.PartyIdentification
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours

/** 台帳(partner01 だけ)と表の置き場の代わり。入れ替えた表と出どころを残す。 */
private class CodeFakes :
    PartnerAgreements,
    CodeTableStore {
    val replaced = mutableListOf<Triple<String, CodeTable, CodeTableSource>>()

    override suspend fun find(partnerId: String): Result<PartnerAgreement?, DomainError> =
        ok(
            if (partnerId == "partner01") {
                PartnerAgreement(
                    "partner01",
                    PartyIdentification("S", "14"),
                    PartyIdentification("R", "14"),
                    "R",
                    setOf(Currency.JPY),
                    true,
                    4.hours,
                )
            } else {
                null
            },
        )

    override suspend fun replace(
        partnerId: String,
        table: CodeTable,
        source: CodeTableSource,
    ): Result<Int, DomainError> {
        replaced += Triple(partnerId, table, source)
        return ok(replaced.size + 1)
    }

    fun service() = ImportCodeTableService(this, this)
}

private fun request(
    partnerId: String = "partner01",
    vararg rows: List<String>,
) = CodeTableImport(
    partnerId,
    CodeKind.ITEMS,
    CodeKind.ITEMS.columns,
    rows.mapIndexed { index, values -> CodeRow(index + 2, values) },
    "a".repeat(64),
    "ops-1",
)

class ImportCodeTableSpec :
    FunSpec({
        test("検査を通った表で入れ替え、版と行の数を返す。出どころ(ファイルの SHA-256・運用者)を置き場に渡す") {
            val fakes = CodeFakes()

            fakes.service()(request(rows = arrayOf(listOf("gtin", "4901234567900", "prod-2", "SKU-2")))) shouldBe
                ok(ImportedCodeTable(version = 2, rows = 1))

            fakes.replaced.single() shouldBe
                Triple(
                    "partner01",
                    CodeTable.Items(mapOf(ItemCode.Gtin("4901234567900") to InternalItem("prod-2", "SKU-2"))),
                    CodeTableSource("a".repeat(64), "ops-1"),
                )
        }

        test("ファイルに誤りがあれば、何も入れ替えずに誤りの一覧を返す") {
            val fakes = CodeFakes()

            val result = fakes.service()(request(rows = arrayOf(listOf("ean", "1", "p", "s"))))

            (result as Result.Err).error shouldBe
                InvalidCodeTable(listOf(CodeTableViolation(2, "code_type", "gtin か buyer_part_number にしてください")))
            result.error.message shouldBe "取り込むファイルに誤りがあります(1 件): 2 行目 code_type: gtin か buyer_part_number にしてください"
            fakes.replaced.shouldBeEmpty()
        }

        test("台帳にない取引先の表は入れ替えない") {
            val fakes = CodeFakes()

            (fakes.service()(request("partner99", listOf("gtin", "4901234567900", "p", "s"))) as Result.Err).error shouldBe
                UnknownPartner("partner99")
            fakes.replaced.map { it.first }.shouldContainExactly(emptyList())
        }
    })
