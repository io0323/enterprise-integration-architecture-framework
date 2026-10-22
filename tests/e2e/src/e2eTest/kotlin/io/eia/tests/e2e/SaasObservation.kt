@file:Suppress("MagicNumber") // 待ち時間

package io.eia.tests.e2e

import io.eia.tests.e2e.E2eEnvironment.PROMETHEUS
import io.eia.tests.e2e.E2eEnvironment.TEMPO
import io.eia.tests.e2e.E2eEnvironment.encode
import io.eia.tests.e2e.E2eEnvironment.get
import io.eia.tests.e2e.E2eEnvironment.json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.avro.generic.GenericRecord
import java.time.Duration

// SaaS 連携の E2E(SaasE2E)の観察の部品: Kafka の Canonical の顧客のイベント・Prometheus・Tempo(公開されたエンドポイントだけ)

internal const val CUSTOMER_CHANGED = "sales.customer.changed.v1"

/** Canonical の顧客のイベント(キーは顧客 ID)。[enough] 件そろうか [timeout] まで読む。 */
internal fun customerEvents(
    customerId: String,
    enough: Int = 1,
    timeout: Duration = Duration.ofSeconds(90),
): List<Pair<GenericRecord, String?>> =
    SagaKafka.keyed(CUSTOMER_CHANGED, customerId, enough, timeout).mapNotNull { record ->
        record.value()?.let(SagaKafka::decode)?.let { it to SagaKafka.header(record, "traceparent") }
    }

internal fun GenericRecord.customerName(): String = (get("customer") as GenericRecord).get("name").toString()

internal fun GenericRecord.sourceVersion(): Long = get("sourceVersion") as Long

/** Prometheus の即時の問い合わせの値の合計(系列がなければ 0)。 */
internal fun metricSum(expr: String): Double =
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

/** Tempo の trace に含まれるサービスの名前。 */
internal fun traceServiceNames(traceId: String): Set<String> =
    orEmptyOnError { json(get("$TEMPO/api/traces/$traceId")).jsonObject["batches"]?.jsonArray.orEmpty() }
        .flatMap { batch ->
            batch.jsonObject
                .getValue("resource")
                .jsonObject["attributes"]
                ?.jsonArray
                .orEmpty()
                .map { it.jsonObject }
                .filter { it.getValue("key").jsonPrimitive.content == "service.name" }
                .map {
                    it
                        .getValue("value")
                        .jsonObject
                        .getValue("stringValue")
                        .jsonPrimitive.content
                }
        }.toSet()

/** Tempo はまだ取り込んでいない trace に 404 を返す(E2eEnvironment.get は例外にする)。 */
internal fun <T> orEmptyOnError(block: () -> List<T>): List<T> =
    try {
        block()
    } catch (
        @Suppress("TooGenericExceptionCaught") _: Exception,
    ) {
        emptyList()
    }
