@file:Suppress("MagicNumber") // 状態コード・件数・待ち時間・時刻の単位

package io.eia.tests.e2e.nfr

import io.eia.shared.kernel.Result
import io.eia.shared.sdk.mqtt.MqttConfig
import io.eia.shared.sdk.mqtt.MqttMessage
import io.eia.shared.sdk.mqtt.MqttPassword
import io.eia.shared.sdk.mqtt.MqttPublisher
import io.eia.tests.e2e.E2eEnvironment
import io.eia.tests.e2e.E2eEnvironment.Client
import io.eia.tests.e2e.E2eEnvironment.GATEWAY
import io.eia.tests.e2e.header
import kotlinx.coroutines.runBlocking
import org.apache.avro.LogicalTypes
import org.apache.avro.generic.GenericRecord
import java.net.URI
import java.net.http.HttpRequest
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

private val repoRoot: Path = Path.of(System.getProperty("eiaf.repo.root", "."))

/**
 * INT-SALES-005(Webhook の受け口。Gateway 経由の応答時間)・INT-INTEGRATION-001(受け付けた Webhook → integration-flow)・
 * INT-SALES-007(顧客のイベントの発行)。Gateway の Webhook のルートの枠(送り手の IP ごとに 120 件 / 60 秒。環境変数で変えられない)の中で送る。
 */
internal fun webhooks(
    report: Report,
    requests: Int,
) {
    val key =
        Files.readString(repoRoot.resolve("infra/local/secrets/crm-webhook/sender-current")).removeSuffix("\n").let {
            if (it.startsWith("whsec_")) Base64.getDecoder().decode(it.removePrefix("whsec_")) else it.toByteArray()
        }
    val since = System.currentTimeMillis()
    val latencies = Latencies()
    val statuses = Counts()
    val correlations = ConcurrentHashMap.newKeySet<String>()
    val interval = Duration.ofMillis(550) // 約 109 件 / 60 秒
    repeat(requests) { i ->
        val id = "cus_nfr_${HexFormat.of().formatHex(ByteArray(4).also(SecureRandom()::nextBytes))}"
        val body =
            """{"type":"customer.created","data":{"id":"$id","display_name":"NFR $i","email":"$id@example.com",""" +
                """"created_at":"2026-11-07T00:00:00Z","updated_at":"2026-11-07T00:00:00Z","version":1}}"""
        val now = Instant.now().epochSecond
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val signature = "v1," + Base64.getEncoder().encodeToString(mac.doFinal("evt_$id.$now.$body".toByteArray()))
        val request =
            HttpRequest
                .newBuilder(URI.create("$GATEWAY/sales/v1/webhooks/crm"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("webhook-id", "evt_$id")
                .header("webhook-timestamp", now.toString())
                .header("webhook-signature", signature)
                .header("X-Correlation-Id", "nfr-webhook-$since-$i")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        val started = System.nanoTime()
        val response = orNullOnIoError { E2eEnvironment.send(request) }
        latencies.addNanos(System.nanoTime() - started)
        statuses.add("HTTP ${response?.statusCode() ?: "io-error"}")
        response?.header("X-Correlation-Id")?.let(correlations::add)
        Thread.sleep(interval.toMillis())
    }
    report.value("INT-SALES-005 status", statuses.json())
    report.latency("INT-SALES-005 webhook ack (client, via Gateway)", latencies.summary())
    webhookEvents(report, since, correlations)
}

/** 受け付けた Webhook([correlations])の、取り込みのトピックと顧客のイベントの遅延。 */
private fun webhookEvents(
    report: Report,
    since: Long,
    correlations: Set<String>,
) {
    val received = ConcurrentHashMap<String, Long>()
    val changed = ConcurrentHashMap<String, Long>()
    val publishReceived = Latencies()
    val publishChanged = Latencies()
    Events.read(listOf("integration.crm-webhook.received.v1", "sales.customer.changed.v1"), since, done = {
        changed.size >= correlations.size
    }) { record ->
        val correlation = Events.header(record, "correlationid")?.takeIf { it in correlations } ?: return@read
        val ceTime = Events.ceTime(record) ?: return@read
        if (record.topic() == "integration.crm-webhook.received.v1") {
            received.putIfAbsent(correlation, ceTime)
            publishReceived.addMillis((record.timestamp() - ceTime).toDouble())
        } else {
            changed.putIfAbsent(correlation, ceTime)
            publishChanged.addMillis((record.timestamp() - ceTime).toDouble())
        }
    }
    val flow = Latencies()
    received.forEach { (correlation, start) -> changed[correlation]?.let { flow.addMillis((it - start).toDouble()) } }
    report.value("webhook events", "受け付け ${correlations.size} / received ${received.size} / changed ${changed.size}")
    report.latency("publish integration.crm-webhook.received.v1 (Outbox → Kafka)", publishReceived.summary())
    report.latency("INT-INTEGRATION-001 (received → customer.changed を Outbox に書く)", flow.summary())
    report.latency("INT-SALES-007 publish sales.customer.changed.v1 (Outbox → Kafka)", publishChanged.summary())
}

/** INT-IOT-001 / INT-IOT-002: デバイス(SDK の MQTT)が送ってから、`iot.telemetry.reported.v1` で読めるまで(どちらの時刻もホストの時計)。 */
internal fun iot(
    report: Report,
    requests: Int,
    rate: Double,
) {
    val tenant = "t1"
    val device = "d-001"
    val password = devicePassword(tenant, device)
    val boot = HexFormat.of().formatHex(ByteArray(8).also(SecureRandom()::nextBytes))
    val sentAt = ConcurrentHashMap<Long, Long>()
    val latencies = Latencies()
    val arrivedCount = AtomicInteger()
    val since = System.currentTimeMillis()
    val reader =
        thread(name = "nfr-iot-reader") {
            Events.read(
                listOf("iot.telemetry.reported.v1"),
                since,
                ReadLimits(Duration.ofMinutes(10), Duration.ofSeconds(90)),
                done = { arrivedCount.get() == requests },
            ) { record ->
                val arrived = System.nanoTime()
                if (record.key()?.toString(Charsets.UTF_8) != "$tenant/$device") return@read
                val payload = record.value()?.let(Events::decode)?.takeIf { it.get("bootId").toString() == boot } ?: return@read
                val sent = sentAt[payload.get("seq") as Long] ?: return@read
                latencies.addNanos(arrived - sent)
                arrivedCount.incrementAndGet()
            }
        }
    Thread.sleep(3_000) // 読み手の割り当てを待つ
    val failures = Counts()
    runBlocking {
        val publisher =
            MqttPublisher(
                MqttConfig(
                    host = "localhost",
                    port = 19883,
                    clientId = "nfr-$tenant-$device",
                    username = "$tenant/$device",
                    password = MqttPassword(password),
                ),
            )
        try {
            val interval = (1000 / rate).toLong()
            for (seq in 0 until requests) {
                val payload = """{"v":2,"boot":"$boot","seq":$seq,"readings":[{"name":"temperature","value":21.5,"unit":"Cel"}]}"""
                sentAt[seq.toLong()] = System.nanoTime()
                val result = publisher.publish(MqttMessage("devices/$tenant/$device/telemetry", payload.toByteArray()))
                if (result !is Result.Ok) failures.add("publish failed")
                Thread.sleep(interval)
            }
        } finally {
            publisher.close()
        }
    }
    reader.join(TimeUnit.MINUTES.toMillis(10))
    report.value("INT-IOT-001 sent", "$requests 件(${"%.1f".format(rate)} 件/秒、QoS 1)/ 失敗 ${failures.total()}")
    report.latency("INT-IOT-001+002 device publish → Kafka readable (host clock)", latencies.summary())
}

/** デバイスのパスワード(`make env` が作る infra/local/secrets/mqtt/device-passwords。値は出力しない)。 */
private fun devicePassword(
    tenant: String,
    device: String,
): String =
    Files
        .readAllLines(repoRoot.resolve("infra/local/secrets/mqtt/device-passwords"))
        .map { it.trim().split(Regex("\\s+")) }
        .first { it.size == 2 && it[0] == "$tenant/$device" }[1]

/** INT-SALES-003: レガシーの受注表の変更(コミットの時刻 `source.committedAt`)から、整形したイベントを Kafka に書くまで。 */
internal fun cdc(
    report: Report,
    batches: Int,
) {
    val since = System.currentTimeMillis()
    val numbers = mutableSetOf<String>()
    repeat(batches) {
        numbers += legacySimulate("seed", "50")
        numbers += legacySimulate("advance", "50")
    }
    val latencies = Latencies()
    Events.read(listOf("sales.legacy-order.changed.v1"), since, ReadLimits(quiet = Duration.ofSeconds(60))) { record ->
        if (record.key()?.toString(Charsets.UTF_8) !in numbers) return@read
        val payload = record.value()?.let(Events::decode) ?: return@read
        val source = payload.get("source") as GenericRecord
        if (source.get("snapshot") == true) return@read
        val committed = source.get("committedAt") as Long
        val logical =
            source.schema
                .getField("committedAt")
                .schema()
                .logicalType
        val committedMillis = if (logical is LogicalTypes.TimestampMicros) committed / 1000 else committed
        latencies.addMillis((record.timestamp() - committedMillis).toDouble())
    }
    report.value("INT-SALES-003 changes", "legacy-sim の seed 50 と advance 50 を $batches 回(受注 ${numbers.size} 件)")
    report.latency("INT-SALES-003 legacy commit → Kafka (record time)", latencies.summary())
}

/** レガシーのアプリの操作(compose の legacy-sim。LegacyOrderAclE2E と同じ入口)。 */
private fun legacySimulate(vararg args: String): List<String> {
    val infra = repoRoot.resolve("infra/local")
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
            "--profile",
            "legacy-sim-cli",
            "run",
            "--rm",
            "--no-deps",
            "legacy-sim",
            "simulate",
        ) + args
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val output = process.inputStream.bufferedReader().readLines()
    check(process.waitFor(3, TimeUnit.MINUTES) && process.exitValue() == 0) { "legacy-sim の simulate ${args.toList()} が失敗しました" }
    // 変えた受注番号(1 行に 1 件)
    return output.filter { Regex("^J[0-9]{9}$").matches(it) }
}

/**
 * `caller_deadline` のしきい値(ADR-0045 §4)の見直し。bff-graphql → Gateway(注文の読み取り)に Toxiproxy で揺れのある遅延を入れ、
 * 打ち切りの割合と、画面の遅延の SLO(1 秒を超えた割合)・部分応答の割合を並べる。`make up ... E2E=1` の基盤で実行する。
 */
internal fun callerDeadlineSweep(
    report: Report,
    rate: Double,
    seconds: Int,
) {
    val ids = shippedOrders(20)
    val settings = listOf(0 to 0, 600 to 400, 1000 to 600, 1300 to 600)
    // 計らない: 温めて、caller_deadline の系列を作ってから始める(各段の後の scrape が、次の段の窓の前の値になる)
    warmUpStorefront(report, ids, rate)
    Prometheus.awaitScrape()
    settings.forEach { (latency, jitter) ->
        if (latency > 0) toxic(latency, jitter)
        val phaseStart = Instant.now()
        try {
            val results = loadStorefront(StorefrontQuery.SINGLE, ids, listOf(Client.A), rate, seconds)
            val label = "delay $latency±${jitter}ms"
            report.value("$label outcomes", results.outcomes.json())
            report.value("$label > 1s ratio", "%.4f".format(results.latencies.ratioAbove(1000.0)))
            report.latency("$label (client)", results.latencies.summary())
            Prometheus.awaitScrape()
            callerDeadline(report, Prometheus.window(phaseStart), "$label ")
        } finally {
            resetToxics()
        }
    }
}

private fun toxic(
    latency: Int,
    jitter: Int,
) {
    toxiproxy(
        "/proxies/gateway-from-bff/toxics",
        """{"name":"nfr_latency","type":"latency","stream":"downstream","attributes":{"latency":$latency,"jitter":$jitter}}""",
    )
}

private fun resetToxics() {
    toxiproxy("/reset", "{}")
}

private fun toxiproxy(
    path: String,
    body: String,
) {
    val response =
        E2eEnvironment.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:19474$path"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)),
        )
    check(response.statusCode() in 200..299) { "Toxiproxy の $path が ${response.statusCode()}" }
}
