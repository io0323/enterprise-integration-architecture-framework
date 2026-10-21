package io.eia.integrationflow.application

import io.eia.integrationflow.application.port.inbound.TransformedRecord
import io.eia.integrationflow.application.usecase.LoadFlowsService
import io.eia.integrationflow.application.usecase.TransformRecordService
import io.eia.integrationflow.domain.FlowDefinition
import io.eia.integrationflow.domain.MappedRecord
import io.eia.integrationflow.domain.MappedValue
import io.eia.integrationflow.domain.MappingNode
import io.eia.integrationflow.domain.MappingRule
import io.eia.integrationflow.domain.Ordering
import io.eia.integrationflow.domain.Route
import io.eia.integrationflow.domain.SourcePath
import io.eia.integrationflow.domain.SourceValue
import io.eia.integrationflow.domain.Transform
import io.eia.integrationflow.domain.Trigger
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun path(text: String) = checkNotNull(SourcePath.parse(text))

private fun flow(id: String = "crm-customer"): FlowDefinition =
    (
        FlowDefinition.of(
            id,
            1,
            "INT-SALES-007",
            "sales.Customer",
            listOf(Trigger.Event("integration.crm-webhook.received.v1", "crm", setOf("customer.updated"), path("$.data"))),
            Ordering("id", path("$.version")),
            mapOf(
                "id" to MappingNode.Field(MappingRule(path("$.id"), Transform.Copy)),
                "email" to MappingNode.Field(MappingRule(path("$.email"), Transform.Lowercase)),
            ),
            emptyMap(),
            listOf(Route(null, "sales.customer.changed.v1")),
        ) as Result.Ok
    ).value

class FlowServicesSpec :
    FunSpec({
        test("1 件を写し、新旧の判定のキーと版・届け先を決める") {
            val record =
                SourceValue.Obj(
                    mapOf(
                        "id" to SourceValue.Text("cus_1"),
                        "email" to SourceValue.Text("A@B.C"),
                        "version" to SourceValue.Number("4"),
                    ),
                )
            TransformRecordService()(flow(), record) shouldBe
                ok(
                    TransformedRecord(
                        "crm-customer",
                        "cus_1",
                        4,
                        MappedRecord(mapOf("id" to MappedValue.Text("cus_1"), "email" to MappedValue.Text("a@b.c"))),
                        "sales.customer.changed.v1",
                    ),
                )
        }

        test("版・キー・値の誤りは、最初の誤りで止める") {
            val transform = TransformRecordService()
            (transform(flow(), SourceValue.Obj(mapOf("id" to SourceValue.Text("cus_1")))) as Result.Err).error.field shouldBe "$.version"
            (transform(flow(), SourceValue.Obj(mapOf("version" to SourceValue.Number("1")))) as Result.Err).error.field shouldBe "id"
            val badEmail =
                SourceValue.Obj(
                    mapOf(
                        "id" to SourceValue.Text("c"),
                        "email" to SourceValue.Obj(emptyMap()),
                        "version" to SourceValue.Number("1"),
                    ),
                )
            (transform(flow(), badEmail) as Result.Err).error.field shouldBe "email"
        }

        test("読み込み: 置き場の誤りはそのまま返す。同じ ID の重複は一覧の誤り") {
            LoadFlowsService { ok(listOf(flow())) }().let { (it as Result.Ok).value.flows.map { f -> f.id } } shouldBe
                listOf("crm-customer")
            val broken = ValidationError(listOf(FieldViolation("contracts/flows/x.v1.flow.yaml", "壊れています")))
            LoadFlowsService { err(broken) }() shouldBe err(broken)
            (LoadFlowsService { ok(listOf(flow(), flow())) }() as Result.Err)
                .error.violations
                .single()
                .field shouldBe "id"
        }
    })
