package io.eia.legacyorderacl.adapters.reconcile

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.postgresql.ds.PGSimpleDataSource

class JdbcSnapshotRequestsSpec :
    FunSpec({
        val requests = JdbcSnapshotRequests(PGSimpleDataSource())

        test("指定した注文番号だけを取り直す Incremental Snapshot の指示(キーの順。SQL の文字列のリテラル)") {
            val data = Json.parseToJsonElement(requests.signalData(setOf("J000000004", "J000000002"))).jsonObject
            data["type"]!!.jsonPrimitive.content shouldBe "incremental"
            data["data-collections"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("public.t_juchu")
            val condition = data["additional-conditions"]!!.jsonArray.single().jsonObject
            condition["data-collection"]!!.jsonPrimitive.content shouldBe "public.t_juchu"
            condition["filter"]!!.jsonPrimitive.content shouldBe "col_02 IN ('J000000002', 'J000000004')"
        }

        test("注文番号の ' は SQL のリテラルの中で '' にし、JSON は正しくエスケープする(条件の外に出られない)") {
            val data = Json.parseToJsonElement(requests.signalData(setOf("J'); DROP TABLE x; --\""))).jsonObject
            data["additional-conditions"]!!
                .jsonArray
                .single()
                .jsonObject["filter"]!!
                .jsonPrimitive.content shouldBe
                "col_02 IN ('J''); DROP TABLE x; --\"')"
        }

        test("制御文字を含む注文番号は書かない") {
            requests.requestSnapshot(setOf("J\u0000")).shouldBeInstanceOf<Result.Err<UnexpectedError>>()
        }
    })
