package io.eia.inventory.adapters

import com.github.avrokotlin.avro4k.Avro
import io.eia.inventory.adapters.inbound.ReleaseStockV1
import io.eia.inventory.adapters.inbound.ReserveStockV1
import io.eia.inventory.adapters.inbound.StockLineV1
import io.eia.inventory.adapters.out.outbox.InventoryEventSchemas
import io.eia.inventory.adapters.out.outbox.StockRejectionReasonV1
import io.eia.inventory.adapters.out.outbox.StockReleaseOutcomeV1
import io.eia.inventory.adapters.out.outbox.StockReleasedV1
import io.eia.inventory.adapters.out.outbox.StockReservationRejectedV1
import io.eia.inventory.adapters.out.outbox.StockReservedV1
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.KSerializer
import org.apache.avro.Schema
import java.io.File

private fun contract(path: String): String = File(checkNotNull(System.getProperty("eia.contractsAvro")), path).readText()

/** [value] を契約のスキーマで書いて読み戻す(@SerialName が契約の record・enum の名前と一致していることの確認。ADR-0025 §1)。 */
private fun <T> roundTrip(
    schema: String,
    serializer: KSerializer<T>,
    value: T,
): T {
    val parsed = Schema.Parser().parse(schema)
    return Avro.decodeFromByteArray(parsed, serializer, Avro.encodeToByteArray(parsed, serializer, value))
}

class InventoryContractsSpec :
    FunSpec({
        test("返事のトピックと、リソースのスキーマは契約のファイルの内容と同じ") {
            InventoryEventSchemas.subjects.map { it.topic } shouldBe
                listOf("inventory.stock.reserved.v1", "inventory.stock.reservation-rejected.v1", "inventory.stock.released.v1")
            InventoryEventSchemas.stockReserved.schema shouldBe contract("inventory/StockReserved.avsc")
            InventoryEventSchemas.stockReservationRejected.schema shouldBe contract("inventory/StockReservationRejected.avsc")
            InventoryEventSchemas.stockReleased.schema shouldBe contract("inventory/StockReleased.avsc")
        }

        test("返事の型を契約のスキーマで書いて読める(enum を含む)") {
            roundTrip(InventoryEventSchemas.stockReserved.schema, StockReservedV1.serializer(), StockReservedV1("s", "o")) shouldBe
                StockReservedV1("s", "o")
            val rejected = StockReservationRejectedV1("s", "o", StockRejectionReasonV1.INSUFFICIENT_STOCK)
            roundTrip(InventoryEventSchemas.stockReservationRejected.schema, StockReservationRejectedV1.serializer(), rejected) shouldBe
                rejected
            val released = StockReleasedV1("s", "o", StockReleaseOutcomeV1.NOT_RESERVED)
            roundTrip(InventoryEventSchemas.stockReleased.schema, StockReleasedV1.serializer(), released) shouldBe released
        }

        test("コマンドの型を契約のスキーマで読み書きできる") {
            val reserve = ReserveStockV1("s", "o", listOf(StockLineV1(1, "SKU-1", 2), StockLineV1(2, "SKU-2", 1)))
            roundTrip(contract("inventory/ReserveStock.avsc"), ReserveStockV1.serializer(), reserve) shouldBe reserve
            roundTrip(contract("inventory/ReleaseStock.avsc"), ReleaseStockV1.serializer(), ReleaseStockV1("s", "o")) shouldBe
                ReleaseStockV1("s", "o")
        }
    })
