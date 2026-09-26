package io.eia.tools.contract

import io.eia.tools.contract.rules.Naming
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class NamingSpec :
    FunSpec({
        mapOf(
            "sales.order.created.v1" to null,
            "sales.order.created.v12" to null,
            "sales.order.created.v1.dlq" to null,
            "logistics.shipment.delivery-scheduled.v2" to null,
            "inventory.stock.cmd-reserve.v1" to null,
            "inventory.stock.cmd-release-all.v1.dlq" to null,
            "sales.order.created" to Rule.NAMING_TOPIC,
            "sales.order.created.v0" to Rule.NAMING_TOPIC,
            "Sales.order.created.v1" to Rule.NAMING_TOPIC,
            "sales.order_item.created.v1" to Rule.NAMING_TOPIC,
            "sales.order.created.v1.retry" to Rule.NAMING_TOPIC,
            "sales.order.created-.v1" to Rule.NAMING_TOPIC,
            "inventory.stock.cmd.v1" to Rule.NAMING_COMMAND_TOPIC,
            "inventory.stock.cmdreserve.v1" to Rule.NAMING_COMMAND_TOPIC,
            "inventory.stock.cmd-.v1" to Rule.NAMING_TOPIC,
        ).forEach { (topic, expected) ->
            test("Topic '$topic' → ${expected?.id ?: "準拠"}") {
                Naming.checkTopic(topic)?.first shouldBe expected
            }
        }

        test("コマンドトピックの判定は event セグメントの cmd- prefix による") {
            Naming.isCommandTopic("inventory.stock.cmd-reserve.v1") shouldBe true
            Naming.isCommandTopic("inventory.stock.reserved.v1") shouldBe false
            Naming.isCommandTopic("cmd-x.stock.reserved.v1") shouldBe false
        }

        mapOf(
            "http://localhost:9080/sales" to "sales",
            "https://api.example.com/sales/" to "sales",
            "/logistics" to "logistics",
            "http://localhost:9080/sales/v1" to null,
            "http://localhost:9080/Sales" to null,
            "http://localhost:9080" to null,
        ).forEach { (url, domain) ->
            test("servers '$url' の domain → ${domain ?: "違反"}") {
                val (actual, violation) = Naming.checkServerUrl(url)
                actual shouldBe domain
                (violation == null) shouldBe (domain != null)
            }
        }

        mapOf(
            "/v1/orders" to true,
            "/v1/orders/{orderId}" to true,
            "/v2/order-lines/{lineId}/cancel" to true,
            "/orders" to false,
            "/v1" to false,
            "/v1/Orders" to false,
            "/v1/order_lines" to false,
            "/sales/v1/orders" to false,
        ).forEach { (path, ok) ->
            test("path '$path' → ${if (ok) "準拠" else "違反"}") {
                (Naming.checkApiPath(path) == null) shouldBe ok
            }
        }

        mapOf(
            "sales_daily.v1.yaml" to true,
            "manifest.v1.schema.json" to true,
            "edi/edifact-orders.v1.yaml" to true,
            "README.md" to true,
            "sales_daily.yaml" to false,
            "SalesDaily.v1.yaml" to false,
            "sales-daily.v1.yaml" to false,
            "manifest.schema.json" to false,
        ).forEach { (file, ok) ->
            test("contracts/files/$file → ${if (ok) "準拠" else "違反"}") {
                (Naming.checkFileSpec(file) == null) shouldBe ok
            }
        }

        test("連携 ID・Consumer Group・契約ファイル名") {
            Naming.integrationDomain("INT-SALES-001") shouldBe "sales"
            Naming.integrationDomain("INT-sales-001").shouldBeNull()
            Naming.integrationDomain("INT-SALES-1").shouldBeNull()
            Naming.checkConsumerGroup("inventory.reservation").shouldBeNull()
            Naming.checkConsumerGroup("inventory")?.first shouldBe Rule.NAMING_CONSUMER_GROUP
            Naming.contractFileVersion("order-api.v1.yaml") shouldBe 1
            Naming.contractFileVersion("order-api.yaml").shouldBeNull()
        }
    })
