@file:Suppress("MagicNumber") // 状態コード・待ち時間・パーティション数

package io.eia.tests.e2e

import io.eia.tests.e2e.E2eEnvironment.ORDERS
import io.eia.tests.e2e.E2eEnvironment.request
import io.eia.tests.e2e.E2eEnvironment.sendRespectingRateLimit
import io.eia.tests.e2e.SagaKafka.header
import io.eia.tests.e2e.SagaKafka.keyed
import io.eia.tests.e2e.SagaKafka.payloadWith
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import java.net.http.HttpRequest
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val ORDER_CANCELLED = "sales.order.cancelled.v1"
private const val RESERVE = "inventory.stock.cmd-reserve.v1"
private const val RELEASE = "inventory.stock.cmd-release.v1"
private const val RELEASED = "inventory.stock.released.v1"
private const val PARTITIONS = 3

private fun orderBody(
    sku: String = "SKU-1",
    amount: String = "100",
    country: String = "JP",
) = """{"customerId":"cust-e2e",""" +
    """"lines":[{"productId":"prod-1","sku":"$sku","quantity":1,"unitPrice":{"amount":"$amount","currency":"JPY"}}],""" +
    """"shippingAddress":{"countryCode":"$country","postalCode":"100-0001","city":"Chiyoda","line1":"1-1"}}"""

/** Gateway 経由で注文を作り、注文 ID を返す。 */
private fun place(body: String): String {
    val created =
        sendRespectingRateLimit(
            request(ORDERS)
                .header("Idempotency-Key", "e2e-${UUID.randomUUID()}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)),
        )
    created.statusCode() shouldBe 201
    return Regex(""""id":"([^"]+)"""").find(created.body())?.groupValues?.get(1) ?: error("注文 ID がありません")
}

private fun status(orderId: String): String? {
    val got = sendRespectingRateLimit(request("$ORDERS/$orderId").GET())
    return Regex(""""status":"([A-Z]+)"""").find(got.body())?.groupValues?.get(1)
}

/** 注文が [expected] になるまで待つ(最大 3 分)。なれば true。 */
private fun reaches(
    orderId: String,
    expected: String,
): Boolean = eventually(Duration.ofMinutes(3), Duration.ofSeconds(2)) { status(orderId).takeIf { it == expected } } != null

private fun cancelledReason(orderId: String): String? =
    keyed(ORDER_CANCELLED, orderId)
        .firstOrNull()
        ?.value()
        ?.let(SagaKafka::decode)
        ?.get("reason")
        ?.toString()

private val infra: Path = Path.of(System.getProperty("eiaf.repo.root", ".")).resolve("infra/local")

/** 障害の注入(参加者のコンテナの停止・起動)。tests/e2e はサービスをブラックボックスとして扱い、compose の操作で止める。 */
private fun compose(vararg args: String) {
    val command =
        listOf(
            "docker",
            "compose",
            "-f",
            infra.resolve("docker-compose.yml").toString(),
            "--env-file",
            infra.resolve("images.env").toString(),
            "--env-file",
            infra.resolve(".env").toString(),
            // 参加者は saga の profile のサービス。依存先(otel-collector など)の core も付けないと、compose が構成を読めない
            "--profile",
            "core",
            "--profile",
            "saga",
        ) + args
    val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    check(process.waitFor(3, TimeUnit.MINUTES) && process.exitValue() == 0) { "docker compose ${args.toList()} が失敗しました" }
}

/** DLQ の Replay の CLI(tools/dlq-replay。make dlq-replay と同じ)。終了コードと出力を返す。 */
private fun dlqReplay(vararg args: String): Pair<Int, String> {
    val binary = checkNotNull(System.getProperty("eiaf.dlqReplay")) { "eiaf.dlqReplay がありません(make e2e で実行してください)" }
    val process = ProcessBuilder(listOf(binary) + args).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(3, TimeUnit.MINUTES)) { "dlq-replay が終わりません" }
    return process.exitValue() to output
}

/** 処理の誤りで隔離された在庫の解放のコマンド(DeadLetterPublisher と同じ形: 元の値とヘッダに `eiaf.dlq.*` を加えたもの)。 */
private fun quarantinedRelease(
    sagaId: String,
    ceId: String,
): ProducerRecord<ByteArray?, ByteArray?> {
    val headers =
        mapOf(
            "ce_id" to ceId,
            "ce_source" to "/sales/order-service",
            "ce_type" to "inventory.stock.cmd-release",
            "ce_specversion" to "1.0",
            "ce_time" to Instant.now().toString(),
            "traceparent" to "00-${UUID.randomUUID().toString().replace("-", "")}-00f067aa0ba902b7-01",
            "correlationid" to "e2e-replay-$sagaId",
            "eiaf.dlq.reason" to "UNEXPECTED_EXCEPTION",
            "eiaf.dlq.detail" to "e2e",
            "eiaf.dlq.source.topic" to RELEASE,
            "eiaf.dlq.source.partition" to "0",
            "eiaf.dlq.source.offset" to "0",
            "eiaf.dlq.attempts" to "4",
            "eiaf.dlq.failed-at" to Instant.now().toString(),
        ).map { (name, value) -> RecordHeader(name, value.toByteArray()) }
    val value = SagaKafka.encode(RELEASE, mapOf("sagaId" to sagaId, "orderId" to "e2e-order-$sagaId"))
    return ProducerRecord("$RELEASE.dlq", null, sagaId.toByteArray(), value, headers)
}

/**
 * ROADMAP P07 の DoD: 注文 Saga(order-service が Orchestrator。ADR-0029)の補償が、在庫不足・決済の失敗・タイムアウトで走る。
 * Poison Message は DLQ に隔離され、本流は止まらない。DLQ から Replay で再処理できる(ADR-0028)。
 *
 * 公開されたエンドポイント(Gateway・Kafka・Schema Registry)と、参加者のコンテナの停止(障害の注入)だけを使う。
 * 模擬の規則(ADR-0029 §7): `SKU-SOLDOUT` は在庫 0、決済の上限は 1,000,000 円、出荷は `JP` だけ。
 * 段の期限は compose の 30 秒(`ORDER_SAGA_STEP_TIMEOUT`)。基盤は `make up PROFILE="order saga"`。
 */
class SagaE2E :
    FunSpec({
        test("正常: 在庫の引当 → 決済の承認 → 出荷で、注文は SHIPPED になる") {
            val orderId = place(orderBody())
            reaches(orderId, "SHIPPED") shouldBe true
        }

        test("在庫不足: 補償なしで注文は CANCELLED、sales.order.cancelled.v1 の理由は STOCK_UNAVAILABLE") {
            val orderId = place(orderBody(sku = "SKU-SOLDOUT"))
            reaches(orderId, "CANCELLED") shouldBe true
            cancelledReason(orderId) shouldBe "STOCK_UNAVAILABLE"
        }

        test("決済の失敗: 引き当てた在庫を解放してから CANCELLED(PAYMENT_DECLINED)") {
            val orderId = place(orderBody(amount = "2000000"))
            reaches(orderId, "CANCELLED") shouldBe true
            cancelledReason(orderId) shouldBe "PAYMENT_DECLINED"
            payloadWith(RELEASED, "orderId", orderId)?.get("outcome")?.toString() shouldBe "RELEASED"
        }

        test("出荷の拒否: 決済の承認を取り消し、在庫を解放してから CANCELLED(SHIPMENT_REJECTED)") {
            val orderId = place(orderBody(country = "US"))
            reaches(orderId, "CANCELLED") shouldBe true
            cancelledReason(orderId) shouldBe "SHIPMENT_REJECTED"
            payloadWith("payment.payment.voided.v1", "orderId", orderId)?.get("outcome")?.toString() shouldBe "VOIDED"
            payloadWith(RELEASED, "orderId", orderId)?.get("outcome")?.toString() shouldBe "RELEASED"
        }

        test("タイムアウト: inventory が止まっている間に引当の期限を過ぎると解放を送り、再開の後に CANCELLED(TIMED_OUT)。在庫は残らない") {
            compose("stop", "inventory-service")
            val orderId =
                try {
                    place(orderBody()).also { orderId ->
                        // 期限(30 秒。DB の時計)を過ぎると、返信を待たずに補償(在庫の解放)のコマンドを送る
                        payloadWith(RELEASE, "orderId", orderId) shouldNotBe null
                        status(orderId) shouldBe "PLACED"
                    }
                } finally {
                    // start は依存先(1 回だけ動くコンテナ)も動かし直すので、このサービスだけを起こし、healthy まで待つ
                    compose("up", "-d", "--no-deps", "--no-build", "--wait", "inventory-service")
                }

            // 再開した inventory は、引当と解放の指示を(届いた順に関係なく)処理し、在庫を残さない(ADR-0029 §5 の印)
            reaches(orderId, "CANCELLED") shouldBe true
            cancelledReason(orderId) shouldBe "TIMED_OUT"
            payloadWith(RELEASED, "orderId", orderId)?.get("outcome")?.toString() shouldBeIn listOf("RELEASED", "NOT_RESERVED")
        }

        test("Poison Message: ヘッダのない値はパーティションごとに DLQ に隔離され、その後ろの注文の Saga は止まらずに SHIPPED になる") {
            val key = "e2e-poison-${UUID.randomUUID()}"
            // 全部のパーティションに入れ、どのパーティションに入る Saga のコマンドも Poison Message の後ろに並ぶようにする
            repeat(PARTITIONS) { partition ->
                SagaKafka.produce(ProducerRecord(RESERVE, partition, key.toByteArray(), "not-avro".toByteArray()))
            }
            val orderId = place(orderBody())
            reaches(orderId, "SHIPPED") shouldBe true

            val quarantined = keyed("$RESERVE.dlq", key, enough = PARTITIONS)
            quarantined.size shouldBe PARTITIONS
            quarantined.forEach {
                header(it, "eiaf.dlq.reason") shouldBe "INVALID_HEADERS"
                header(it, "eiaf.dlq.source.topic") shouldBe RESERVE
            }
        }

        test("Replay: DLQ のコマンドは dry-run では送らず、--execute で元のトピックに戻すと(ce_id のまま)参加者が処理して返信する") {
            // 処理の誤りで隔離され、誤りを直した後に戻す(docs/runbooks/event-dlq-replay.md の UNEXPECTED_EXCEPTION)。取消の指示なので在庫は変わらない
            val sagaId = UUID.randomUUID().toString()
            val ceId = UUID.randomUUID().toString()
            SagaKafka.produce(quarantinedRelease(sagaId, ceId))

            val (dryRunCode, dryRun) = dlqReplay("--topic", "$RELEASE.dlq", "--limit", "1", "--key", sagaId)
            dryRunCode shouldBe 0
            dryRun shouldContain ceId
            keyed(RELEASE, sagaId, timeout = Duration.ofSeconds(10)) shouldBe emptyList()

            val (executeCode, executed) = dlqReplay("--topic", "$RELEASE.dlq", "--limit", "1", "--key", sagaId, "--execute")
            executeCode shouldBe 0
            executed shouldContain ceId
            // 戻したコマンドは同じ ce_id(冪等消費の単位)で、inventory が処理して返信する(引当がないので NOT_RESERVED)
            keyed(RELEASE, sagaId).map { header(it, "ce_id") } shouldBe listOf(ceId)
            payloadWith(RELEASED, "sagaId", sagaId)?.get("outcome")?.toString() shouldBe "NOT_RESERVED"
            // この返信の Saga は order-service にないので、order-service は返信を DLQ に隔離する(知らない Saga。ADR-0029)
            keyed("$RELEASED.dlq", sagaId).map { header(it, "eiaf.dlq.reason") } shouldBe listOf("NOT_FOUND")
        }
    })
