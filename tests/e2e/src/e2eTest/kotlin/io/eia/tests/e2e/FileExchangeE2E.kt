@file:Suppress("MagicNumber", "TooManyFunctions") // 件数・待ち時間・時刻。ファイルの用意と観察の小さな関数を、このシナリオのファイルにまとめる

package io.eia.tests.e2e

import io.eia.tests.e2e.E2eEnvironment.PROMETHEUS
import io.eia.tests.e2e.E2eEnvironment.TEMPO
import io.eia.tests.e2e.E2eEnvironment.encode
import io.eia.tests.e2e.E2eEnvironment.get
import io.eia.tests.e2e.E2eEnvironment.json
import io.eia.tests.e2e.E2eEnvironment.secret
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

private val TOKYO: ZoneId = ZoneId.of("Asia/Tokyo")
private val infra: Path = Path.of(System.getProperty("eiaf.repo.root", ".")).resolve("infra/local")
private const val BUCKET = "eiaf-file-exchange"
private const val INBOX = "inbox/sales-daily/"
private const val ARCHIVE = "archive/sales-daily/"
private const val OUTBOX = "/config/outbox"
private const val PARTNER = "partner01"
private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

/** compose のコマンド(core・batch・file・b2b の profile)。 */
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
        "--profile",
        "file",
        "--profile",
        "b2b",
    ) + args

/** コマンドを実行し、終了コードと出力を返す。[environment] は子のプロセスに渡す環境変数(値をコマンドの引数に出さない)。 */
private fun run(
    command: List<String>,
    environment: Map<String, String> = emptyMap(),
    timeout: Duration = Duration.ofMinutes(5),
): Pair<Int, String> {
    val process =
        ProcessBuilder(command).redirectErrorStream(true).apply { environment().putAll(environment) }.start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) { "${command.take(4)} が終わりません" }
    return process.exitValue() to output
}

private fun compose(vararg args: String): Pair<Int, String> = run(composeCommand(*args))

/** batch-etl のコマンドを 1 回だけ実行する(`make batch-etl` と同じ。運用者の操作)。 */
private fun batchEtl(vararg args: String): Pair<Int, String> = compose("run", "--rm", "--no-deps", "batch-etl", *args)

/** DB を管理者で読む(結果の観察だけ。サービスはブラックボックスのまま)。 */
private fun sql(
    database: String,
    query: String,
): String = compose("exec", "-T", "postgres", "psql", "-U", "postgres", "-d", database, "-tAc", query).second.trim()

private fun fx(query: String): String = sql("file_exchange", query)

private fun received(name: String): String =
    fx("SELECT state || ' ' || destination || ' ' || coalesce(reason, '-') FROM exchange.received_file WHERE file_name = '$name'")

/** b2b-gateway が取引先 partner01 に送った記録の数(P12 ⑦b から、取引先への送信は b2b-gateway。ADR-0040 §7)。 */
private fun delivered(name: String): String =
    sql("b2b_gateway", "SELECT count(*) FROM b2b.file_delivery WHERE name = '$name' AND partner_id = '$PARTNER'")

private fun receiveRun(name: String): String =
    fx("SELECT status FROM batch.batch_run WHERE dag_id = 'file-exchange.receive' AND partition_key = '$name'")

/** 取引先(sftp-b2b)の outbox のファイルの名前(コンテナの中を見る。観察だけ)。 */
private fun outbox(): List<String> =
    compose("exec", "-T", "sftp-b2b", "ls", "-1", OUTBOX)
        .second
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/** AWS CLI のコンテナで S3 互換ストレージを操作する([script] は bash の 1 行)。資格情報は環境変数で渡す。 */
private fun s3(
    accessKeyVariable: String,
    secretKeyVariable: String,
    work: Path,
    script: String,
): Pair<Int, String> {
    val image =
        Files.readAllLines(infra.resolve("images.env")).first { it.startsWith("AWS_CLI_IMAGE=") }.substringAfter('=')
    val command =
        listOf("docker", "run", "--rm", "--network", "eiaf", "-e", "AWS_ACCESS_KEY_ID", "-e", "AWS_SECRET_ACCESS_KEY") +
            listOf("-e", "AWS_DEFAULT_REGION=us-east-1", "-v", "$work:/work", "--entrypoint", "bash", image, "-c", script)
    return run(command, mapOf("AWS_ACCESS_KEY_ID" to secret(accessKeyVariable), "AWS_SECRET_ACCESS_KEY" to secret(secretKeyVariable)))
}

private const val AWS = "aws --endpoint-url http://seaweedfs:8333"

/** 置き方(改竄・欠損の注入)。 */
private enum class Damage { NONE, TAMPERED, MISSING }

/**
 * 送り手(batch-etl の identity。受け口に書くだけ)として、契約(sales_daily.v1)どおりの CSV(gzip)とマニフェストを置き、名前を返す
 * ([damages] の 1 つごとに 1 ファイル。1 つのコンテナでまとめて置く)。データファイルを全部置いてから、マニフェストを置く(完了の合図。ADR-0032 §2)。
 * [Damage.TAMPERED] はデータの 1 バイトを変えて置く(大きさは同じで SHA-256 が違う)。[Damage.MISSING] はマニフェストだけを置く。
 */
private fun publish(vararg damages: Damage): List<String> {
    val work = Files.createTempDirectory("e2e-file")
    try {
        Files.createDirectories(work.resolve("data"))
        Files.createDirectories(work.resolve("manifests"))
        // 同じ秒の名前が重ならないよう、連番は乱数の起点から順に振る
        val stamp = STAMP.format(Instant.now())
        val first = Random.nextInt(100, 900)
        val names =
            damages.mapIndexed { index, damage ->
                val name = "sales_daily_${stamp}_${first + index}"
                val header = "business_date,order_id,ordered_at,status,total_minor_units,currency,ship_country,ship_region"
                val rows = (1..3).map { "2026-10-14,e2e-$name-$it,2026-10-14T01:00:0$it.000000Z,PLACED,${1000 * it},JPY,JP,JP-13" }
                val data =
                    ByteArrayOutputStream()
                        .also { out ->
                            GZIPOutputStream(
                                out,
                            ).use { it.write((listOf(header) + rows).joinToString("\r\n", postfix = "\r\n").toByteArray()) }
                        }.toByteArray()
                val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data))
                if (damage == Damage.TAMPERED) data[data.size / 2] = (data[data.size / 2].toInt() xor 0x01).toByte()
                if (damage != Damage.MISSING) Files.write(work.resolve("data/$name.csv.gz"), data)
                Files.writeString(
                    work.resolve("manifests/$name.manifest.json"),
                    """{"file":"$name.csv.gz","recordCount":3,"sha256":"$sha256","schemaVersion":"sales_daily.v1",""" +
                        """"createdAt":"${Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)}",""" +
                        """"traceparent":"00-${HexFormat.of().formatHex(Random.nextBytes(16))}-00f067aa0ba902b7-01",""" +
                        """"correlationId":"e2e-$name","sizeBytes":${data.size}}""",
                )
                name
            }
        val target = "s3://$BUCKET/$INBOX"
        val (exit, output) =
            s3(
                "FILE_BATCH_ETL_S3_ACCESS_KEY",
                "FILE_BATCH_ETL_S3_SECRET_KEY",
                work,
                "set -e; $AWS s3 cp /work/data/ $target --recursive; $AWS s3 cp /work/manifests/ $target --recursive",
            )
        withClue(output) { exit shouldBe 0 }
        return names
    } finally {
        work.toFile().deleteRecursively()
    }
}

/** archive のマニフェストを管理者で読む。 */
private fun archivedManifest(name: String): String {
    val work = Files.createTempDirectory("e2e-manifest")
    try {
        val (exit, output) = s3("S3_ACCESS_KEY", "S3_SECRET_KEY", work, "$AWS s3 cp s3://$BUCKET/$ARCHIVE$name.manifest.json -")
        withClue(output) { exit shouldBe 0 }
        return output
    } finally {
        work.toFile().deleteRecursively()
    }
}

/** Prometheus の即時の問い合わせの値の合計(結果がなければ 0)。 */
private fun prometheusSum(expr: String): Double =
    json(get("$PROMETHEUS/api/v1/query?query=${encode(expr)}"))
        .jsonObject
        .getValue("data")
        .jsonObject
        .getValue("result")
        .jsonArray
        .sumOf {
            it.jsonObject
                .getValue("value")
                .jsonArray[1]
                .jsonPrimitive.content
                .toDouble()
        }

private fun quarantined(reason: String): Double =
    prometheusSum(
        "sum(eia_file_exchange_files_received_total" +
            """{file_exchange_destination="quarantine",file_exchange_reason="$reason"})""",
    )

private fun failedDeliveries(): Double =
    prometheusSum("""sum(eia_b2b_file_deliveries_total{trading_partner_id="$PARTNER",b2b_outcome="failed"})""")

private fun openedCircuits(): Double =
    prometheusSum("""sum(eia_resilience_circuit_breaker_transitions_total{eia_dependency_name="sftp-$PARTNER",to="open"})""")

/** Tempo の trace に span を出したサービスの名前。 */
private fun traceServices(traceId: String): Set<String> =
    json(get("$TEMPO/api/traces/$traceId"))
        .jsonObject["batches"]
        ?.jsonArray
        .orEmpty()
        .mapNotNull { batch ->
            batch.jsonObject
                .getValue("resource")
                .jsonObject["attributes"]
                ?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it.getValue("key").jsonPrimitive.content == "service.name" }
                ?.getValue("value")
                ?.jsonObject
                ?.getValue("stringValue")
                ?.jsonPrimitive
                ?.content
        }.toSet()

/**
 * ROADMAP P09 の DoD(ADR-0032)。基盤は `make up PROFILE="order cdc saga batch file b2b" E2E=1`。
 * P12 ⑦b から、取引先への送信は b2b-gateway が行う(file-exchange は受け渡しの場所に写すだけ。ADR-0040 §7)。
 *
 * 1. 注文 → 分析のバッチ → 日次の売上ファイル → file-exchange の受け取り → 受け渡し → b2b-gateway の取引先(sftp-b2b)への送信が、
 *    本物の流れでつながる。マニフェストの traceparent で、送り手(batch-etl)と受け手(file-exchange)の span が Tempo の同じトレースにある。
 * 2. 改竄・欠損のファイルを受け手が拒否する(quarantine。受け渡さないので、取引先には送らない)。
 * 3. 取引先の SFTP を止めると、送信の失敗と Circuit Breaker の Open が Prometheus まで届き、戻した後は b2b-gateway が次の回で送り終える
 *    (運用者の再実行は要らない)。
 *
 * 公開されたエンドポイント(Gateway・Kafka・S3・Tempo・Prometheus)と、運用者のコマンド(compose の run)・コンテナの停止(障害の注入)・
 * DB とコンテナの中の観察だけを使う。
 */
class FileExchangeE2E :
    FunSpec({
        test("DoD: 注文から取引先への配送までが本物の流れでつながり、マニフェストの traceparent で送り手と受け手が同じトレースになる") {
            val day = LocalDate.now(TOKYO)
            val order = placeOrder()
            awaitCreated(setOf(order))
            batchEtl("run", day.toString(), "--restart").let { (exit, output) -> withClue(output) { exit shouldBe 0 } }
            // 日次の DAG の成功で常駐の batch-etl も売上ファイルの DAG を起動する(AfterDag)。どちらが置いても、版は実行の記録から読む
            batchEtl("sales-file", day.toString(), "--restart").let { (exit, output) -> withClue(output) { exit shouldBe 0 } }
            val generation =
                eventually {
                    sql(
                        "batch_etl",
                        "SELECT generation FROM batch.batch_run WHERE dag_id = 'batch-etl.sales-file' AND partition_key = '$day' " +
                            "AND status = 'SUCCEEDED'",
                    ).toIntOrNull()
                }.shouldNotBeNull()
            // 名前は業務の日の締め(翌日 00:00 JST = その日の 15:00 UTC)と版(ADR-0031 §8)
            val name = "sales_daily_${day.format(DateTimeFormatter.BASIC_ISO_DATE)}150000_${generation.toString().padStart(3, '0')}"

            withClue("受け取って archive に収める") {
                eventually { received(name).takeIf { it == "STORED ARCHIVE -" } } shouldBe "STORED ARCHIVE -"
            }
            withClue("b2b-gateway が取引先に送る") { eventually { delivered(name).takeIf { it == "1" } } shouldBe "1" }
            val dataFile = fx("SELECT data_file FROM exchange.received_file WHERE file_name = '$name'")
            outbox() shouldContain "$dataFile.pgp"

            val manifest = json(archivedManifest(name)).jsonObject
            withClue("受け付けた注文を含む") {
                manifest
                    .getValue("recordCount")
                    .jsonPrimitive.content
                    .toInt() shouldBeGreaterThanOrEqual 1
            }
            val traceId =
                manifest
                    .getValue("traceparent")
                    .jsonPrimitive.content
                    .split('-')[1]
            withClue("Tempo の trace $traceId に送り手と受け手の span がある") {
                eventually(Duration.ofSeconds(60)) {
                    traceServices(traceId).takeIf { it.containsAll(setOf("batch-etl", "file-exchange")) }
                }.shouldNotBeNull() shouldContainAll setOf("batch-etl", "file-exchange")
            }
        }

        test("DoD: 改竄したファイルは checksum_mismatch、データファイルのないマニフェストは data_missing で quarantine に移し、取引先に送らない") {
            val (tampered, missing) = publish(Damage.TAMPERED, Damage.MISSING)
            eventually { received(tampered).takeIf { it.startsWith("STORED") } } shouldBe "STORED QUARANTINE checksum_mismatch"
            eventually { received(missing).takeIf { it.startsWith("STORED") } } shouldBe "STORED QUARANTINE data_missing"
            delivered(tampered) shouldBe "0"
            delivered(missing) shouldBe "0"
            outbox().none { it.startsWith(tampered) || it.startsWith(missing) } shouldBe true
            withClue("quarantine の数が理由ごとに Prometheus に出る(FileExchangeQuarantined の元)") {
                eventually(Duration.ofSeconds(60)) {
                    val reasons = listOf("checksum_mismatch", "data_missing")
                    reasons.takeIf {
                        it.all { reason ->
                            quarantined(reason) > 0
                        }
                    }
                }.shouldNotBeNull()
            }
        }

        test("取引先の SFTP を止めると、送信の失敗と Circuit Breaker の Open が Prometheus に届き、戻した後は b2b-gateway が次の回で送り終える") {
            val started = System.nanoTime()
            // 段階ごとの経過の秒(E2E の所要時間を見るため。CI の出力に残る)
            val phase = { label: String -> println("取引先の停止と回復: $label ${Duration.ofNanos(System.nanoTime() - started).toSeconds()} 秒") }
            val failedBefore = failedDeliveries()
            val openedBefore = openedCircuits()
            var running = false
            compose("stop", "sftp-b2b").let { (exit, output) -> withClue(output) { exit shouldBe 0 } }
            phase("取引先の SFTP を止めた")
            try {
                // 4 つ送る。Circuit Breaker の判定(直近 20 回のうち 10 回以上で失敗率 50%)とリトライは本番の既定のまま。
                // b2b-gateway は 2 秒ごと(E2E の設定)に受け渡しの場所を一覧して送るので、送れない組の試みが重なって Open になる
                val names = publish(Damage.NONE, Damage.NONE, Damage.NONE, Damage.NONE)
                names.forEach { name ->
                    withClue("$name の受け取りと受け渡しは成功し、取引先には送れていない") {
                        eventually { receiveRun(name).takeIf { it == "SUCCEEDED" } } shouldBe "SUCCEEDED"
                        received(name) shouldBe "STORED ARCHIVE -"
                        delivered(name) shouldBe "0"
                    }
                }
                phase("受け取りと受け渡し(4 つ)")
                withClue("送信の失敗と Circuit Breaker の Open が Prometheus に届く") {
                    eventually(Duration.ofSeconds(90), Duration.ofSeconds(2)) {
                        true.takeIf { failedDeliveries() > failedBefore && openedCircuits() > openedBefore }
                    }.shouldNotBeNull()
                }
                phase("メトリクスが届いた")
                compose("up", "-d", "--wait", "sftp-b2b").let { (exit, output) -> withClue(output) { exit shouldBe 0 } }
                running = true
                phase("取引先の SFTP を戻した")
                names.forEach { name ->
                    withClue("$name を b2b-gateway が次の回で送り終える(再実行は要らない)") {
                        eventually(Duration.ofSeconds(90)) { delivered(name).takeIf { it == "1" } } shouldBe "1"
                    }
                    outbox() shouldContain "$name.csv.gz.pgp"
                }
                phase("送り終えた")
            } finally {
                // 途中で失敗しても、取引先の SFTP は戻しておく(ほかのシナリオのため)
                if (!running) compose("up", "-d", "--wait", "sftp-b2b")
                phase("終わり")
            }
        }
    })
