@file:Suppress("MagicNumber", "TooManyFunctions") // 件数・待ち時間・時刻。障害の注入と観察の小さな関数を、このシナリオのファイルにまとめる

package io.eia.tests.e2e

import io.eia.tests.e2e.E2eEnvironment.ORDERS
import io.eia.tests.e2e.E2eEnvironment.PROMETHEUS
import io.eia.tests.e2e.E2eEnvironment.encode
import io.eia.tests.e2e.E2eEnvironment.get
import io.eia.tests.e2e.E2eEnvironment.json
import io.eia.tests.e2e.E2eEnvironment.request
import io.eia.tests.e2e.E2eEnvironment.sendRespectingRateLimit
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import java.net.http.HttpRequest
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

private val TOKYO: ZoneId = ZoneId.of("Asia/Tokyo")
private const val KAFKA = "localhost:19092"
private const val DAILY = "batch-etl.order-daily"
private val infra: Path = Path.of(System.getProperty("eiaf.repo.root", ".")).resolve("infra/local")

/** compose のコマンド(batch profile。依存先の core も付ける)。 */
private fun composeCommand(vararg args: String): List<String> =
    listOf(
        "docker",
        "compose",
        "-f",
        infra.resolve("docker-compose.yml").toString(),
        "--env-file",
        infra.resolve("images.env").toString(),
        "--env-file",
        infra.resolve(".env").toString(),
        "--profile",
        "core",
        "--profile",
        "batch",
    ) + args

/** compose のコマンドを実行し、終了コードと出力を返す。 */
private fun compose(
    vararg args: String,
    timeout: Duration = Duration.ofMinutes(5),
): Pair<Int, String> {
    val process = ProcessBuilder(composeCommand(*args)).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) { "docker compose ${args.toList()} が終わりません" }
    return process.exitValue() to output
}

/** compose のコマンドを裏で動かす(kill するまで終わらないもの)。 */
private fun composeInBackground(vararg args: String): Process =
    ProcessBuilder(composeCommand(*args)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()

/** batch-etl のコマンドを 1 回だけ実行する(`make batch-etl ARGS=...` と同じ。運用者の操作)。 */
private fun batchEtl(vararg args: String): Pair<Int, String> = compose("run", "--rm", "--no-deps", "batch-etl", *args)

/** 分析の DB(batch_etl)を管理者で読む。テストの観察のためだけに使う(サービスはブラックボックスのまま)。 */
private fun sql(query: String): String =
    compose("exec", "-T", "postgres", "psql", "-U", "postgres", "-d", "batch_etl", "-tAc", query).second.trim()

/**
 * 表をロックしたまま待つ psql を裏で動かす(バッチをその表の書き込みで止める。障害の注入)。[release] で外す。
 * ロックを取ったことを確かめてから返す。
 */
private class TableLock(
    private val table: String,
) : AutoCloseable {
    private val name = "e2e-lock-${UUID.randomUUID().toString().take(8)}"
    private val process =
        composeInBackground(
            "exec",
            "-T",
            "-e",
            "PGAPPNAME=$name",
            "postgres",
            "psql",
            "-U",
            "postgres",
            "-d",
            "batch_etl",
            "-c",
            "BEGIN; LOCK TABLE $table IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(900);",
        )

    init {
        val held =
            eventually(Duration.ofSeconds(60), Duration.ofSeconds(1)) {
                sql(
                    "SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid " +
                        "WHERE a.application_name = '$name' AND l.granted AND l.mode = 'AccessExclusiveLock'",
                ).takeIf { it != "0" }
            }
        check(held != null) { "$table のロックを取れません" }
    }

    fun release() {
        sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name = '$name'")
        process.destroy()
    }

    override fun close() = release()
}

/** batch-etl のアプリのロールの接続が、ロックを待っている(バッチがそこで止まった)。 */
private fun batchBlockedOnLock(): Boolean =
    eventually(Duration.ofSeconds(90), Duration.ofSeconds(1)) {
        sql(
            "SELECT count(*) FROM pg_stat_activity WHERE datname = 'batch_etl' AND usename = 'batch_etl_app' AND wait_event_type = 'Lock'",
        ).takeIf { it != "0" }
    } != null

/** 実行のコンテナを名前で殺す(プロセスの kill。SIGKILL)。 */
private fun kill(container: String) {
    val process = ProcessBuilder("docker", "kill", container).redirectErrorStream(true).start()
    process.inputStream.readAllBytes()
    check(process.waitFor(1, TimeUnit.MINUTES) && process.exitValue() == 0) { "$container を kill できません" }
}

private fun runStatus(partition: String): String =
    sql("SELECT status FROM batch.batch_run WHERE dag_id = '$DAILY' AND partition_key = '$partition'")

private fun stepStatus(
    partition: String,
    step: String,
): String =
    sql(
        "SELECT status || ' ' || coalesce(finished_at::text, '-') FROM batch.batch_step " +
            "WHERE dag_id = '$DAILY' AND partition_key = '$partition' AND step_name = '$step'",
    )

/** Gateway 経由で注文を受け付け、注文 ID を返す。 */
private fun placeOrder(): String {
    val created =
        sendRespectingRateLimit(
            request(ORDERS)
                .header("Idempotency-Key", "e2e-batch-${UUID.randomUUID()}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ORDER_BODY)),
        )
    created.statusCode() shouldBe 201
    return Regex(""""id":"([^"]+)"""").find(created.body())?.groupValues?.get(1) ?: error("注文 ID がありません")
}

/** 注文の受け付けのイベントが Kafka に届くまで待つ(Outbox → Debezium)。 */
private fun awaitCreated(orderIds: Set<String>) {
    val properties =
        mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to KAFKA,
            ConsumerConfig.GROUP_ID_CONFIG to "e2e.batch-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        )
    val seen = mutableSetOf<String>()
    KafkaConsumer(properties, StringDeserializer(), ByteArrayDeserializer()).use { consumer ->
        consumer.subscribe(listOf("sales.order.created.v1"))
        val deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos()
        while (System.nanoTime() < deadline && !seen.containsAll(orderIds)) {
            consumer.poll(Duration.ofMillis(500)).forEach { seen += it.key() }
        }
    }
    withClue("Kafka に届いた注文") { seen.containsAll(orderIds) shouldBe true }
}

private fun quoted(ids: Collection<String>): String = ids.joinToString(",") { "'$it'" }

private fun today(): LocalDate = LocalDate.now(TOKYO)

/**
 * ROADMAP P08 の DoD(ADR-0030・ADR-0031)。基盤は `make up PROFILE="order cdc saga batch"`。
 *
 * 1. 日次のバッチを途中(取り込みの最中・変換の最中)で kill して再実行しても、重複・欠損がゼロ(成功したステップは再実行しない)。
 *    order-service の日次の集計(件数とハッシュ)との照合が一致する。
 * 2. SLA の遅延の予測のアラート(BatchSlaAtRisk)が、期限の前に発火する。
 *
 * 公開されたエンドポイント(Gateway・Kafka・Prometheus)と、運用者のコマンド(`make batch-etl` と同じ compose の run)・
 * コンテナの kill と表のロック(障害の注入)・分析の DB の観察だけを使う。
 */
class BatchE2E :
    FunSpec({
        test("DoD: 取り込みの最中と変換の最中に kill して再実行しても、重複・欠損がなく、order-service の集計と一致する") {
            val day = today().toString()
            val orders = (1..5).map { placeOrder() }.toSet()
            awaitCreated(orders)

            // 1 回目: 取り込みの最中(raw への書き込みがロックで止まった所)で kill する
            TableLock("raw.order_placed").use {
                val running =
                    composeInBackground("run", "--rm", "--no-deps", "--name", "e2e-batch-run-1", "batch-etl", "run", day, "--restart")
                withClue("取り込みがロックで止まる") { batchBlockedOnLock() shouldBe true }
                kill("e2e-batch-run-1")
                running.waitFor(1, TimeUnit.MINUTES)
            }
            withClue("kill の後は RUNNING のまま残る") { runStatus(day) shouldBe "RUNNING" }
            stepStatus(day, "ingest").substringBefore(' ') shouldBe "RUNNING"
            withClue(
                "取り込みの途中の行は確定していない",
            ) { sql("SELECT count(*) FROM raw.order_placed WHERE order_id IN (${quoted(orders)})") shouldBe "0" }

            // 2 回目: 再開した実行を、変換の最中(パーティションの置換がロックで止まった所)で kill する
            TableLock("mart.order_daily").use {
                val running = composeInBackground("run", "--rm", "--no-deps", "--name", "e2e-batch-run-2", "batch-etl", "run", day)
                withClue("変換がロックで止まる") { batchBlockedOnLock() shouldBe true }
                eventually(
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(1),
                ) { stepStatus(day, "transform").takeIf { it.startsWith("RUNNING") } }
                kill("e2e-batch-run-2")
                running.waitFor(1, TimeUnit.MINUTES)
            }
            runStatus(day) shouldBe "RUNNING"
            val ingested = stepStatus(day, "ingest")
            ingested.substringBefore(' ') shouldBe "SUCCEEDED"
            stepStatus(day, "transform").substringBefore(' ') shouldBe "RUNNING"
            sql("SELECT count(*) FROM raw.order_placed WHERE order_id IN (${quoted(orders)})") shouldBe "5"

            // 3 回目: 再実行は、成功した取り込みを飛ばして変換から再開し、成功する
            val (exit, output) = batchEtl("run", day)
            withClue(output) { exit shouldBe 0 }
            runStatus(day) shouldBe "SUCCEEDED"
            withClue("成功した取り込みは再実行しない") { stepStatus(day, "ingest") shouldBe ingested }
            stepStatus(day, "transform").substringBefore(' ') shouldBe "SUCCEEDED"
            withClue("重複も欠損もない") {
                sql(
                    "SELECT count(*), count(DISTINCT order_id) FROM mart.order_daily WHERE business_date = '$day' AND order_id IN (${quoted(
                        orders,
                    )})",
                ) shouldBe
                    "5|5"
            }

            // order-service の日次の集計と、件数とハッシュが一致する(照合の範囲は実行の日の前日まで。明日を実行の日にする)
            val (reconciled, report) = batchEtl("reconcile", today().plusDays(1).toString(), "--restart")
            withClue(report) { reconciled shouldBe 0 }
            sql("SELECT outcome FROM mart.reconciliation WHERE run_date = '${today().plusDays(1)}' AND business_date = '$day'") shouldBe
                "MATCH"
        }

        test("DoD: 期限に遅れる見込みになると、期限の前に SLA の遅延の予測のアラート(BatchSlaAtRisk)が発火する") {
            // 見込みは実績(成功した 5 回以上)の p90。実績が少ない間は定義の見込み(各ステップ 5 分)を使う(ADR-0030 §6)。
            // このシナリオは、取り込みが止まったまま「取り込み 5 分 + 変換 5 分」の見込みが期限を超えることで発火させる
            withClue("日次の DAG の取り込みの実績が 5 回未満(make clean した基盤で実行する)") {
                sql(
                    "SELECT count(*) < 5 FROM batch.batch_step WHERE dag_id = '$DAILY' AND step_name = 'ingest' AND status = 'SUCCEEDED'",
                ) shouldBe
                    "t"
            }
            // 期限は「前日の分を今日の SLA の時刻までに」。今から 5〜6 分後を期限にした serve に差し替える(日をまたぐなら待つ)
            var now = ZonedDateTime.now(TOKYO)
            if (now.hour == 23 && now.minute >= 50) {
                Thread.sleep(Duration.between(now, now.plusDays(1).truncatedTo(ChronoUnit.DAYS).plusMinutes(1)).toMillis())
                now = ZonedDateTime.now(TOKYO)
            }
            val deadline = now.plusMinutes(6).truncatedTo(ChronoUnit.MINUTES)
            val yesterday = now.toLocalDate().minusDays(1).toString()
            compose("stop", "batch-etl").first shouldBe 0
            try {
                compose(
                    "run",
                    "-d",
                    "--no-deps",
                    "--name",
                    "e2e-batch-serve",
                    "-e",
                    "BATCH_ETL_SLA_AT=${deadline.format(DateTimeFormatter.ofPattern("HH:mm"))}",
                    "-e",
                    "BATCH_ETL_POLL_INTERVAL=5s",
                    "batch-etl",
                    "serve",
                ).first shouldBe 0
                // 取り込む行がある状態で、前日の分を最初から実行し、取り込みの最中で止める
                val order = placeOrder()
                awaitCreated(setOf(order))
                TableLock("raw.order_placed").use {
                    val running =
                        composeInBackground(
                            "run",
                            "--rm",
                            "--no-deps",
                            "--name",
                            "e2e-batch-run-sla",
                            "batch-etl",
                            "run",
                            yesterday,
                            "--restart",
                        )
                    try {
                        withClue("取り込みがロックで止まる") { batchBlockedOnLock() shouldBe true }
                        val firing =
                            eventually(
                                Duration.ofSeconds(Duration.between(ZonedDateTime.now(TOKYO), deadline).seconds),
                                Duration.ofSeconds(5),
                            ) {
                                val alerts = json(get("$PROMETHEUS/api/v1/alerts")).jsonObject["data"]!!.jsonObject["alerts"]!!.jsonArray
                                alerts
                                    .map { it.jsonObject }
                                    .firstOrNull {
                                        val labels = it["labels"]!!.jsonObject
                                        labels["alertname"]?.jsonPrimitive?.content == "BatchSlaAtRisk" &&
                                            labels["batch_dag"]?.jsonPrimitive?.content == DAILY &&
                                            it["state"]?.jsonPrimitive?.content == "firing"
                                    }
                            }
                        withClue("期限(${deadline.toLocalTime()} JST)の前に BatchSlaAtRisk が firing になる") { firing shouldNotBe null }
                        val slack =
                            json(get("$PROMETHEUS/api/v1/query?query=${encode("min(eia_batch_sla_slack_seconds{batch_dag=\"$DAILY\"})")}"))
                                .jsonObject["data"]!!
                                .jsonObject["result"]!!
                                .jsonArray
                        slack.shouldNotBeEmpty()
                        withClue("余裕が負") {
                            val value =
                                slack[0]
                                    .jsonObject["value"]!!
                                    .jsonArray[1]
                                    .jsonPrimitive.content
                                    .toDouble()
                            (value < 0) shouldBe true
                        }
                    } finally {
                        kill("e2e-batch-run-sla")
                        running.waitFor(1, TimeUnit.MINUTES)
                    }
                }
            } finally {
                ProcessBuilder("docker", "rm", "-f", "e2e-batch-serve").redirectErrorStream(true).start().waitFor(1, TimeUnit.MINUTES)
                compose("up", "-d", "--no-deps", "--no-build", "--wait", "batch-etl").first shouldBe 0
            }
            // 後始末: 止めた前日の分を再開して終える。常駐の serve も RUNNING のまま残った分を再開するので、
            // どちらかが終えるまで待つ(同時に動かすと片方はロックで何もしない)
            val finished =
                eventually(Duration.ofMinutes(3), Duration.ofSeconds(5)) {
                    runStatus(yesterday).takeIf { it == "SUCCEEDED" } ?: run {
                        batchEtl("run", yesterday)
                        null
                    }
                }
            withClue("前日の分が再開して成功する") { finished shouldBe "SUCCEEDED" }
        }
    })
