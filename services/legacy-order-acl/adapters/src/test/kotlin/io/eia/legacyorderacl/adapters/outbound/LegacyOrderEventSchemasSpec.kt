package io.eia.legacyorderacl.adapters.outbound

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LegacyOrderEventSchemasSpec :
    FunSpec({
        test("トピックは sales.legacy-order.changed.v1、スキーマは契約のファイルの内容") {
            LegacyOrderEventSchemas.LEGACY_ORDER_CHANGED.name shouldBe "sales.legacy-order.changed.v1"
            LegacyOrderEventSchemas.legacyOrderChanged.schema shouldBe
                java.io.File(checkNotNull(System.getProperty("eia.contractsAvro")), "sales/LegacyOrderChanged.avsc").readText()
        }

        test("顧客名は toString に出さない") {
            val event =
                LegacyOrderChangedV1(
                    "J1",
                    "C1",
                    "山田商事",
                    LegacyOrderStatusV1.ACCEPTED,
                    MoneyV1(1, "JPY"),
                    kotlin.time.Instant.parse("2026-10-08T00:00:00Z"),
                    null,
                    LegacyChangeSourceV1(1, kotlin.time.Instant.parse("2026-10-08T00:00:00Z"), false),
                )
            event.toString() shouldBe "LegacyOrderChangedV1(orderNumber=J1, status=ACCEPTED, ***)"
        }
    })
