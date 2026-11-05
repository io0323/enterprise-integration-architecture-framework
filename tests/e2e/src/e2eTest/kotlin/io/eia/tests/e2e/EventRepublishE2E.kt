@file:Suppress("MagicNumber", "TooManyFunctions") // 状態コード・待ち時間。障害の注入と観察の小さな関数を、このシナリオのファイルにまとめる

package io.eia.tests.e2e

import io.eia.tests.e2e.E2eEnvironment.ORDERS
import io.eia.tests.e2e.E2eEnvironment.http
import io.eia.tests.e2e.E2eEnvironment.request
import io.eia.tests.e2e.E2eEnvironment.sendRespectingRateLimit
import io.eia.tests.e2e.SagaKafka.decode
import io.eia.tests.e2e.SagaKafka.header
import io.eia.tests.e2e.SagaKafka.keyed
import io.eia.tests.e2e.SagaKafka.records
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.admin.RecordsToDelete
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val CREATED = "sales.order.created.v1"
private const val STATUS_CHANGED = "sales.order.status-changed.v1"
private const val CANCELLED = "sales.order.cancelled.v1"
private const val CONNECTOR = "http://localhost:19083/connectors/order-outbox"
private const val SLOT = "order_outbox"
private const val REPUBLISHED = "eiaf.republished"
private const val PARTITIONS = 3

private val repoRoot: Path = Path.of(System.getProperty("eiaf.repo.root", "."))
private val infra: Path = repoRoot.resolve("infra/local")

/** compose のコマンドを実行し、終了コードと出力を返す(`make order-republish`・`make batch-etl` と同じ操作)。 */
private fun compose(vararg args: String): Pair<Int, String> = compose(args.toList())

private fun compose(args: List<String>): Pair<Int, String> {
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
            // e2e.env がないと、order-service の接続先が make up の時と変わる(ADR-0044)
            "--env-file",
            repoRoot.resolve("tests/e2e/e2e.env").toString(),
            "--profile",
            "core",
            "--profile",
            "order",
            "--profile",
            "batch",
        ) + args
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(5, TimeUnit.MINUTES)) { "docker compose $args が終わりません" }
    return process.exitValue() to output
}

private fun republish(args: List<String>): Pair<Int, String> =
    compose(listOf("run", "--rm", "--no-deps", "order-service", "republish") + args)

private fun batchEtl(vararg args: String): Pair<Int, String> = compose("run", "--rm", "--no-deps", "batch-etl", *args)

/** 管理者で読み書きする。テストの観察と、スロットの削除(運用者の操作。docs/runbooks/cdc-outbox-lag.md)のためだけに使う。 */
private fun sql(
    database: String,
    query: String,
): String = compose("exec", "-T", "postgres", "psql", "-U", "postgres", "-d", database, "-tAc", query).second.trim()

private fun connector(
    method: String,
    path: String = "",
): HttpResponse<String> =
    http.send(
        HttpRequest
            .newBuilder(URI.create("$CONNECTOR$path"))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

private fun slotActive(): String = sql("order_service", "SELECT active FROM pg_replication_slots WHERE slot_name = '$SLOT'")

private fun await(
    what: String,
    condition: () -> Boolean,
) {
    val done = eventually(Duration.ofMinutes(2), Duration.ofSeconds(1)) { condition().takeIf { it } }
    withClue(what) { done shouldBe true }
}

/** スロットを失った状態を作る(無効なスロットからの回復の手順。docs/runbooks/cdc-outbox-lag.md の「lost になったとき」の 2)。 */
private fun dropSlot() {
    connector("PUT", "/stop").statusCode() shouldBe 204
    await("コネクタが止まる") { connector("GET", "/status").body().contains("\"STOPPED\"") }
    await("スロットが使われなくなる") { slotActive() == "f" }
    connector("DELETE", "/offsets").statusCode() shouldBe 200
    sql("order_service", "SELECT pg_drop_replication_slot('$SLOT')")
    slotActive() shouldBe ""
}

/** 発行を再開する(同じ手順の 3)。スロットは Debezium が作り直す。 */
private fun resumeConnector() {
    connector("PUT", "/resume")
    await("スロットが作り直されて使われる") { slotActive() == "t" }
}

private fun orderBody(sku: String = "SKU-1"): String =
    """{"customerId":"cust-e2e",""" +
        """"lines":[{"productId":"prod-1","sku":"$sku","quantity":1,"unitPrice":{"amount":"100","currency":"JPY"}}],""" +
        """"shippingAddress":{"countryCode":"JP","postalCode":"100-0001","city":"Chiyoda","line1":"1-1"}}"""

private fun place(body: String = orderBody()): String {
    val created =
        sendRespectingRateLimit(
            request(ORDERS)
                .header("Idempotency-Key", "e2e-republish-${UUID.randomUUID()}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)),
        )
    created.statusCode() shouldBe 201
    return Regex(""""id":"([^"]+)"""").find(created.body())?.groupValues?.get(1) ?: error("注文 ID がありません")
}

private fun reaches(
    orderId: String,
    expected: String,
): Boolean =
    eventually(Duration.ofMinutes(3), Duration.ofSeconds(2)) {
        Regex(""""status":"([A-Z]+)"""")
            .find(sendRespectingRateLimit(request("$ORDERS/$orderId").GET()).body())
            ?.groupValues
            ?.get(1)
            ?.takeIf { it == expected }
    } != null

private fun isMarked(record: ConsumerRecord<ByteArray?, ByteArray?>): Boolean = header(record, REPUBLISHED) != null

private fun marked(
    topic: String,
    orderId: String,
): ConsumerRecord<ByteArray?, ByteArray?> =
    records(topic) { it.key()?.toString(Charsets.UTF_8) == orderId && isMarked(it) }.firstOrNull()
        ?: error("再発行の $topic が届きません($orderId)")

private fun field(
    record: ConsumerRecord<ByteArray?, ByteArray?>,
    name: String,
): String? = decode(record.value()!!)?.get(name)?.toString()

/** トピックの今の末尾までのレコードを消す(Kafka の保持期間を過ぎて消えた状態を作る。障害の注入)。 */
private fun expireRecords(vararg topics: String) {
    Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:19092")).use { admin ->
        val partitions = topics.flatMap { topic -> (0 until PARTITIONS).map { TopicPartition(topic, it) } }
        val ends = admin.listOffsets(partitions.associateWith { OffsetSpec.latest() }).all().get(30, TimeUnit.SECONDS)
        admin
            .deleteRecords(ends.mapValues { (_, end) -> RecordsToDelete.beforeOffset(end.offset()) })
            .all()
            .get(30, TimeUnit.SECONDS)
    }
}

/**
 * P14 ⑦b(ADR-0046。#80・#116)。消えたイベントを、注文の表から再発行で取り戻す。
 *
 * 1. #80: レプリケーションスロットを失った間の注文のイベントが、再発行で目印つきで届く。作成の時点の内容は元と同じ。
 * 2. #116: Kafka の保持期間を過ぎて消えたイベントを再発行で埋め戻すと、週次の照合が DRIFT から MATCH になる。
 *
 * 公開されたエンドポイント(Gateway・Kafka・Connect の REST)と、運用者のコマンド(`make order-republish`・`make batch-etl` と同じ
 * compose の run)・分析の DB の観察だけを使う。スロットの削除とレコードの削除は障害の注入。
 */
class EventRepublishE2E :
    FunSpec({
        test("#80: スロットを失った間の注文のイベントが再発行で届く。作成の時点の内容は元と同じで、今の状態への遷移は今の版で 1 件") {
            val from = Instant.now().minusSeconds(1)
            val shipped = place()
            reaches(shipped, "SHIPPED") shouldBe true
            val original = keyed(CREATED, shipped).single()
            val lastVersion =
                records(STATUS_CHANGED, enough = 3, timeout = Duration.ofSeconds(30)) { it.key()?.toString(Charsets.UTF_8) == shipped }
                    .maxOf { field(it, "version")!!.toLong() }

            dropSlot()
            val lost =
                try {
                    place()
                } finally {
                    resumeConnector()
                }
            withClue("スロットがない間の注文のイベントは届かない") {
                keyed(CREATED, lost, timeout = Duration.ofSeconds(15)).shouldBeEmpty()
            }

            val range = listOf("--from", from.toString(), "--to", Instant.now().plusSeconds(60).toString())
            val options = range + listOf("--reason", "slot-lost", "--operator", "e2e", "--run-id", "e2e-slot-${UUID.randomUUID()}")

            val (dryRun, dryOutput) = republish(options)
            withClue(dryOutput) { dryRun shouldBe 0 }
            withClue("dry-run は発行しない") { keyed(CREATED, lost, timeout = Duration.ofSeconds(15)).shouldBeEmpty() }

            val (executed, output) = republish(options + "--execute")
            withClue(output) { executed shouldBe 0 }

            val arrived = marked(CREATED, lost)
            header(arrived, REPUBLISHED) shouldBe "true"
            header(arrived, "eiaf.republish.reason") shouldBe "slot-lost"
            withClue("再発行は 1 件だけで、元のイベントはない") {
                keyed(CREATED, lost, enough = 2, timeout = Duration.ofSeconds(10)) shouldHaveSize 1
            }

            // 作成の時点の内容は元と同じ。今の状態が SHIPPED でも、状態は PLACED になる
            val copy = marked(CREATED, shipped)
            withClue("作成の時点の内容") { decode(copy.value()!!).toString() shouldBe decode(original.value()!!).toString() }
            withClue("ce_id は新しい(ADR-0046 §3)") { header(copy, "ce_id") shouldNotBe header(original, "ce_id") }

            // 途中の遷移は戻らない。今の状態への遷移が、今の版で 1 件
            val changed = marked(STATUS_CHANGED, shipped)
            field(changed, "status") shouldBe "SHIPPED"
            field(changed, "previousStatus") shouldBe "PLACED"
            field(changed, "version") shouldBe lastVersion.toString()

            // Saga のコマンドは再発行しない。スロットがない間に送れなかった注文は、段の期限で取り消される(ADR-0046 §5)
            withClue("再発行は Saga を動かし直さない") { reaches(lost, "CANCELLED") shouldBe true }
        }

        test("#116: 保持期間を過ぎて消えたイベントを再発行で埋め戻すと、週次の照合が DRIFT から MATCH になる") {
            val today = LocalDate.now(ZoneId.of("Asia/Tokyo"))
            val day = today.toString()
            val runDay = today.plusDays(1).toString()

            fun reconcileOutcome() =
                sql("batch_etl", "SELECT outcome FROM mart.reconciliation WHERE run_date = '$runDay' AND business_date = '$day'")

            compose("stop", "batch-etl").first shouldBe 0
            try {
                // ここまでのイベントは取り込んでおく(これから消すのは、この後の注文のイベントだけにする)
                batchEtl("run", day, "--restart").let { (exit, output) -> withClue(output) { exit shouldBe 0 } }

                val from = Instant.now().minusSeconds(1)
                val shipped = place()
                val cancelled = place(orderBody(sku = "SKU-SOLDOUT"))
                reaches(shipped, "SHIPPED") shouldBe true
                reaches(cancelled, "CANCELLED") shouldBe true
                records(
                    STATUS_CHANGED,
                ) { it.key()?.toString(Charsets.UTF_8) == shipped && field(it, "status") == "SHIPPED" }.shouldHaveSize(1)
                keyed(CANCELLED, cancelled) shouldHaveSize 1

                expireRecords(CREATED, STATUS_CHANGED, CANCELLED)

                // 消えたことが分かる。取り込みは黙って先に進まない(ADR-0031 §4)
                withClue("消えたまま取り込むと失敗する") { batchEtl("run", day, "--restart").first shouldNotBe 0 }
                batchEtl("skip-lost-events", "--confirm").let { (exit, output) -> withClue(output) { exit shouldBe 0 } }

                // 埋め戻す前は DRIFT(このシナリオが消えた状態を作れていることの確認)
                batchEtl("run", day, "--restart").let { (exit, output) -> withClue(output) { exit shouldBe 0 } }
                batchEtl("reconcile", runDay, "--restart").let { (exit, output) -> withClue(output) { exit shouldBe 3 } }
                reconcileOutcome() shouldBe "DRIFT"

                val (executed, output) =
                    republish(
                        listOf(
                            "--from",
                            from.toString(),
                            "--to",
                            Instant.now().plusSeconds(60).toString(),
                            "--reason",
                            "retention-exceeded",
                            "--operator",
                            "e2e",
                            "--run-id",
                            "e2e-retention-${UUID.randomUUID()}",
                            "--execute",
                        ),
                    )
                withClue(output) { executed shouldBe 0 }
                marked(CREATED, shipped)
                marked(STATUS_CHANGED, shipped)
                marked(CREATED, cancelled)
                marked(CANCELLED, cancelled)

                batchEtl("run", day, "--restart").let { (exit, log) -> withClue(log) { exit shouldBe 0 } }
                batchEtl("reconcile", runDay, "--restart").let { (exit, log) -> withClue(log) { exit shouldBe 0 } }
                withClue("再発行の後の週次の照合") { reconcileOutcome() shouldBe "MATCH" }
                withClue("途中の遷移は戻らない。今の状態への遷移の 1 件だけ") {
                    sql("batch_etl", "SELECT count(*), max(status) FROM raw.order_status_change WHERE order_id = '$shipped'") shouldBe
                        "1|SHIPPED"
                }
            } finally {
                compose("up", "-d", "--no-deps", "--no-build", "--wait", "batch-etl").first shouldBe 0
            }
        }
    })
