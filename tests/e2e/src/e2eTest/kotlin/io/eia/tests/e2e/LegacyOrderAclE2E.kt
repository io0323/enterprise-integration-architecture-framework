@file:Suppress("MagicNumber") // 待ち時間・wire format のバイト数・時刻の許容幅

package io.eia.tests.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val KAFKA = "localhost:19092"
private const val REGISTRY = "http://localhost:19081/apis/registry/v3"
private const val OUTPUT = "sales.legacy-order.changed.v1"
private const val DLQ = "_cdc.legacy.public.t_juchu.dlq"
private val ORDER_NUMBER = Regex("^J[0-9]{9}$")

/**
 * レガシーのアプリの操作(legacy-sim の simulate)。E2E から見たレガシーは外部のシステムなので、サービスのコードを使わず、
 * レガシーのアプリと同じ入口(compose の legacy-sim。`make legacy-simulate` と同じ)で書き換える。変えた受注番号を返す。
 */
private fun legacySimulate(vararg args: String): List<String> {
    val infra = Path.of(System.getProperty("eiaf.repo.root", ".")).resolve("infra/local")
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
            "--build",
            "--no-deps",
            "legacy-sim",
            "simulate",
        ) + args
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val output = process.inputStream.bufferedReader().readLines()
    check(process.waitFor(3, TimeUnit.MINUTES) && process.exitValue() == 0) { "legacy-sim の simulate ${args.toList()} が失敗しました" }
    return output.filter { ORDER_NUMBER.matches(it) }
}

/**
 * [topic] を最初から読み、キーに [orderNumber] を含むレコードを [timeout] まで集める([enough] 件そろったら終わる)。
 * 整形済みのトピックのキーは注文番号の UTF-8。DLQ のキーは生の CDC のキーのまま(Converter の Avro。注文番号の文字列を含む)。
 */
private fun records(
    topic: String,
    orderNumber: String,
    enough: Int = 1,
    timeout: Duration = Duration.ofSeconds(90),
): List<ConsumerRecord<ByteArray?, ByteArray?>> {
    val properties =
        mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to KAFKA,
            ConsumerConfig.GROUP_ID_CONFIG to "e2e.legacy-order-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
        )
    val found = mutableListOf<ConsumerRecord<ByteArray?, ByteArray?>>()
    KafkaConsumer(properties, ByteArrayDeserializer(), ByteArrayDeserializer()).use { consumer ->
        consumer.subscribe(listOf(topic))
        val deadline = System.nanoTime() + timeout.toNanos()
        while (found.size < enough && System.nanoTime() < deadline) {
            found += consumer.poll(Duration.ofMillis(500)).filter { it.key()?.toString(Charsets.UTF_8)?.contains(orderNumber) == true }
        }
    }
    return found
}

private fun header(
    record: ConsumerRecord<ByteArray?, ByteArray?>,
    name: String,
): String? =
    record
        .headers()
        .lastHeader(name)
        ?.value()
        ?.toString(Charsets.UTF_8)

/** 契約のペイロード(wire format の contentId から、書き手のスキーマを Schema Registry で取って読む)。 */
private fun decode(value: ByteArray): GenericRecord {
    val buffer = ByteBuffer.wrap(value)
    buffer.get() shouldBe 0.toByte()
    val contentId = buffer.int
    val schema =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("$REGISTRY/ids/contentIds/$contentId")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    schema.statusCode() shouldBe 200
    return GenericDatumReader<GenericRecord>(Schema.Parser().parse(schema.body()))
        .read(null, DecoderFactory.get().binaryDecoder(value.copyOfRange(5, value.size), null))
}

/**
 * ROADMAP P06: legacy-sim(レガシー DB を模擬)→ Debezium CDC → Anti-Corruption 変換 → 整形済みトピック(ADR-0026・INT-SALES-003)。
 * `make up PROFILE="order cdc"` で起動した基盤に対して、レガシーのアプリの操作と Kafka・Schema Registry だけで確かめる。
 */
class LegacyOrderAclE2E :
    FunSpec({
        test("レガシーに登録した受注が、注文番号のキー・CloudEvents のヘッダ・契約のスキーマで sales.legacy-order.changed.v1 に届く") {
            val before = Instant.now()
            val number = legacySimulate("seed", "1").single()

            val record = records(OUTPUT, number).firstOrNull() ?: error("$OUTPUT に受注 $number が届きません")
            record.key()?.toString(Charsets.UTF_8) shouldBe number

            header(record, "ce_type") shouldBe "sales.legacy-order.changed"
            header(record, "ce_source") shouldBe "/sales/legacy-order-acl"
            header(record, "ce_specversion") shouldBe "1.0"
            header(record, "traceparent")!! shouldMatch Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$")
            val event = decode(checkNotNull(record.value()))
            event.get("orderNumber").toString() shouldBe number
            event.get("status").toString() shouldBe "ACCEPTED"
            (event.get("totalAmount") as GenericRecord).get("currency").toString() shouldBe "JPY"
            // 固定長の末尾の空白は除かれている
            event.get("customerName").toString().endsWith(" ") shouldBe false
            // レガシーは JST の現地時刻で書く。UTC に変換されていれば、登録した時刻(今)に近い(変換しなければ 9 時間ずれる)
            val orderedAtMicros = event.get("orderedAt") as Long
            abs(orderedAtMicros / 1_000 - before.toEpochMilli()) shouldBeLessThan Duration.ofMinutes(5).toMillis()
            event.get("legacyUpdatedAt") shouldBe null
        }

        test("変換できない値の受注は、原因のヘッダを付けて DLQ に入り、整形済みのトピックには出ない") {
            val number = legacySimulate("anomaly", "amount-fraction").single()

            val dead = records(DLQ, number).firstOrNull() ?: error("$DLQ に受注 $number が入りません")
            header(dead, "eiaf.dlq.reason") shouldBe "AMOUNT_HAS_FRACTION"
            header(dead, "eiaf.dlq.source.topic") shouldBe "_cdc.legacy.public.t_juchu"
            header(dead, "eiaf.dlq.detail")!!.contains("1234") shouldBe false
            records(OUTPUT, number, timeout = Duration.ofSeconds(10)).shouldBeEmpty()
        }
    })
