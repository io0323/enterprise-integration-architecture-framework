@file:Suppress("MagicNumber", "TooManyFunctions") // 状態コード・待ち時間。クエリと障害の注入の部品

package io.eia.tests.e2e

import io.eia.tests.e2e.E2eEnvironment.Client
import io.eia.tests.e2e.E2eEnvironment.GATEWAY
import io.eia.tests.e2e.E2eEnvironment.ORDERS
import io.eia.tests.e2e.E2eEnvironment.PROMETHEUS
import io.eia.tests.e2e.E2eEnvironment.encode
import io.eia.tests.e2e.E2eEnvironment.get
import io.eia.tests.e2e.E2eEnvironment.json
import io.eia.tests.e2e.E2eEnvironment.request
import io.eia.tests.e2e.E2eEnvironment.send
import io.eia.tests.e2e.E2eEnvironment.sendRespectingRateLimit
import io.eia.tests.e2e.E2eEnvironment.token
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val STOREFRONT = "$GATEWAY/storefront/v1/graphql"
private const val PROBLEMS = "https://eiaf.example/problems"
private val CALLER_SCOPES = setOf("bff-graphql-audience", "sales.order:read")
private val repoRoot: Path = Path.of(System.getProperty("eiaf.repo.root", "."))

/** Kafka の Broker の停止と再開(start は作り直さない)。 */
private fun compose(vararg args: String) {
    val infra = repoRoot.resolve("infra/local")
    val command =
        listOf("docker", "compose", "-f", infra.resolve("docker-compose.yml").toString()) +
            listOf("--env-file", infra.resolve("images.env").toString(), "--env-file", infra.resolve(".env").toString()) +
            listOf("--env-file", repoRoot.resolve("tests/e2e/e2e.env").toString(), "--profile", "core") + args
    val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    check(process.waitFor(3, TimeUnit.MINUTES) && process.exitValue() == 0) { "docker compose ${args.toList()} が失敗しました" }
}

private fun postOrder(key: String): HttpResponse<String> =
    send(
        request(ORDERS)
            .header("Idempotency-Key", key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(ORDER_BODY)),
    )

private fun orderIdOf(response: HttpResponse<String>): String =
    json(response.body())
        .jsonObject
        .getValue("id")
        .jsonPrimitive.content

/** 注文が [expected] になるまで待つ(Saga の全体。最大 3 分)。なれば true。 */
private fun reaches(
    orderId: String,
    expected: String,
): Boolean =
    eventually(Duration.ofMinutes(3), Duration.ofSeconds(2)) {
        val got = sendRespectingRateLimit(request("$ORDERS/$orderId").GET())
        json(got.body())
            .jsonObject["status"]
            ?.jsonPrimitive
            ?.content
            ?.takeIf { it == expected }
    } != null

private fun statusOf(orderId: String): String? =
    json(sendRespectingRateLimit(request("$ORDERS/$orderId").GET()).body())
        .jsonObject["status"]
        ?.jsonPrimitive
        ?.content

private fun storefrontOrder(orderId: String): HttpResponse<String> =
    sendRespectingRateLimit(
        request(STOREFRONT, client = null)
            .header("Authorization", "Bearer ${token(Client.A, CALLER_SCOPES)}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(buildJsonObject { put("query", """{ order(id: "$orderId") { id } }""") }.toString())),
    )

private fun JsonObject.firstErrorExtensions(): JsonObject? =
    this["errors"]
        ?.jsonArray
        ?.firstOrNull()
        ?.jsonObject
        ?.getValue("extensions")
        ?.jsonObject

private fun metric(expr: String): Double? =
    json(get("$PROMETHEUS/api/v1/query?query=${encode(expr)}"))
        .jsonObject
        .getValue("data")
        .jsonObject
        .getValue("result")
        .jsonArray
        .firstOrNull()
        ?.jsonObject
        ?.getValue("value")
        ?.jsonArray
        ?.get(1)
        ?.jsonPrimitive
        ?.content
        ?.toDouble()

/**
 * ROADMAP P14 の障害注入(遅延・切断・Broker 停止)。期待挙動と結果は docs/reports/p14-resilience.md。経路は ADR-0044。
 * コンテナの停止で確かめる既存のシナリオ(`SagaE2E`・`BffE2E`・`FileExchangeE2E` など)は、ここに移さずレポートから参照する。
 */
class ResilienceE2E :
    FunSpec({
        afterEach { Toxiproxy.reset() }

        context("DB の障害(order-service → PostgreSQL)") {
            test("遅延: リクエストの予算(10 秒)を超えると 503 deadline-exceeded + Retry-After で打ち切る。戻すと読める") {
                val (response, elapsed) =
                    Toxiproxy.whileDelayed(Toxiproxy.ORDER_DB, 12_000) {
                        val started = System.nanoTime()
                        val got = sendRespectingRateLimit(request("$ORDERS/ord-none").GET())
                        got to Duration.ofNanos(System.nanoTime() - started)
                    }
                withClue(response.body()) {
                    response.statusCode() shouldBe 503
                    response.body() shouldContain "$PROBLEMS/deadline-exceeded"
                }
                response.header("Retry-After") shouldNotBe null
                withClue("Gateway のタイムアウト(15 秒。504)より先に返す: $elapsed") { (elapsed < Duration.ofSeconds(14)) shouldBe true }
                eventually(Duration.ofSeconds(60), Duration.ofSeconds(2)) {
                    "recovered".takeIf { sendRespectingRateLimit(request("$ORDERS/ord-none").GET()).statusCode() == 404 }
                }.shouldNotBeNull()
            }

            test("切断: 注文の作成は 503 で 500 にならない。戻した後に同じ Idempotency-Key で送り直すと、注文は 1 件だけ") {
                val key = "e2e-resilience-${UUID.randomUUID()}"
                val during = Toxiproxy.whileCut(Toxiproxy.ORDER_DB) { postOrder(key) }
                withClue(during.body()) { during.statusCode() shouldBe 503 }

                val created =
                    withClue("戻した後の送り直し") {
                        eventually(Duration.ofSeconds(90), Duration.ofSeconds(3)) {
                            postOrder(key).takeIf { it.statusCode() == 201 }
                        }.shouldNotBeNull()
                    }
                val replayed = postOrder(key)
                replayed.statusCode() shouldBe 201
                replayed.header("Idempotent-Replayed") shouldBe "true"
                orderIdOf(replayed) shouldBe orderIdOf(created)
            }
        }

        context("下流の障害(bff-graphql → Gateway → order)") {
            test("遅延: 注文に配った予算(2 秒)で打ち切り、全体の予算(5 秒)の中で部分応答を返す") {
                val started = System.nanoTime()
                val response = Toxiproxy.whileDelayed(Toxiproxy.BFF_GATEWAY, 3_000) { storefrontOrder("ord-none") }
                val elapsed = Duration.ofNanos(System.nanoTime() - started)

                response.statusCode() shouldBe 200
                val body = json(response.body()).jsonObject
                (
                    body
                        .getValue("data")
                        .jsonObject
                        .getValue("order") is JsonNull
                ) shouldBe true
                val error = body.firstErrorExtensions().shouldNotBeNull()
                error.getValue("code").jsonPrimitive.content shouldBe "UPSTREAM_UNAVAILABLE"
                error.getValue("service").jsonPrimitive.content shouldBe "orders"
                withClue("全体の予算の中で返す: $elapsed") { (elapsed < Duration.ofSeconds(5)) shouldBe true }
            }

            test("切断が続くと Circuit Breaker が開き、メトリクスで分かる。戻すと閉じて読める") {
                val breaker = """job="bff-graphql",eia_dependency_name="order-api",state="open""""
                val open = "max(eia_resilience_circuit_breaker_state_ratio{$breaker})"
                Toxiproxy.whileCut(Toxiproxy.BFF_GATEWAY) {
                    withClue("切断の間に Circuit Breaker が開く") {
                        eventually(Duration.ofSeconds(120), Duration.ofSeconds(1)) {
                            storefrontOrder("ord-none")
                            metric(open)?.takeIf { it == 1.0 }
                        }.shouldNotBeNull()
                    }
                }
                withClue("戻すと読める(Half-Open の後)") {
                    eventually(Duration.ofSeconds(90), Duration.ofSeconds(3)) {
                        json(storefrontOrder("ord-none").body())
                            .jsonObject
                            .takeIf { it.firstErrorExtensions() == null }
                    }.shouldNotBeNull()
                }
                withClue("閉じたことがメトリクスで分かる") {
                    eventually(Duration.ofSeconds(60), Duration.ofSeconds(3)) { metric(open)?.takeIf { it == 0.0 } }.shouldNotBeNull()
                }
            }
        }

        context("Kafka の障害") {
            test("inventory → Broker の切断: 切れている間は Saga が進まず、戻すと欠けずに SHIPPED まで進む") {
                val orderId =
                    Toxiproxy.whileCut(Toxiproxy.INVENTORY_KAFKA) {
                        val created = postOrder("e2e-resilience-${UUID.randomUUID()}")
                        created.statusCode() shouldBe 201
                        val id = orderIdOf(created)
                        // Saga の段の期限(30 秒)より短い間だけ切る
                        Thread.sleep(Duration.ofSeconds(8).toMillis())
                        withClue("切れている間は引当の返信が来ない") { statusOf(id) shouldBe "PLACED" }
                        id
                    }
                withClue("戻すと出荷まで進む") { reaches(orderId, "SHIPPED") shouldBe true }
            }

            test("inventory → Broker の遅延: 遅くなるだけで、欠けずに SHIPPED まで進む") {
                val orderId =
                    Toxiproxy.whileDelayed(Toxiproxy.INVENTORY_KAFKA, 1_000) {
                        val created = postOrder("e2e-resilience-${UUID.randomUUID()}")
                        created.statusCode() shouldBe 201
                        orderIdOf(created).also { id -> withClue("遅延の間でも進む") { reaches(id, "SHIPPED") shouldBe true } }
                    }
                orderId shouldNotBe ""
            }

            test("Broker の停止: 注文は受け付ける(Outbox)。再開すると欠けずに SHIPPED まで進む") {
                compose("stop", "kafka")
                val orderId =
                    try {
                        val created = postOrder("e2e-resilience-${UUID.randomUUID()}")
                        withClue(created.body()) { created.statusCode() shouldBe 201 }
                        orderIdOf(created)
                    } finally {
                        compose("start", "kafka")
                    }
                withClue("再開の後に出荷まで進む") { reaches(orderId, "SHIPPED") shouldBe true }
            }
        }
    })
