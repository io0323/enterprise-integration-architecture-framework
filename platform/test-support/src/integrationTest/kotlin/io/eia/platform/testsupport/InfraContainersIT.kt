@file:Suppress("MagicNumber") // 起動を待つ時間

package io.eia.platform.testsupport

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.containers.Network
import org.testcontainers.kafka.KafkaContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private fun get(url: String): HttpResponse<String> =
    HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())

/** compose と同じ構成のテスト用コンテナ(Apicurio・Kafka Connect)が起動し、使う部品がそろっていること(ADR-0016 §5・§9)。 */
class InfraContainersIT :
    FunSpec({
        test("Apicurio Registry: REST(v3)と管理用のポートの両方が準備できてから起動を終える") {
            ApicurioRegistryContainer().use { registry ->
                registry.start()

                get("${registry.baseUrl}/system/info").body() shouldContain "Apicurio Registry"
                registry.internalBaseUrl("apicurio") shouldBe "http://apicurio:8080/apis/registry/v3"
            }
        }

        test("Kafka Connect: compose と同じイメージに、Debezium の Postgres コネクタ・EventRouter・BinaryDataConverter・Filter がある") {
            Network.newNetwork().use { network ->
                KafkaContainer(InfraImages.get("KAFKA_IMAGE"))
                    .withNetwork(network)
                    .withListener("kafka:19092")
                    .withStartupTimeout(Duration.ofMinutes(3))
                    .use { kafka ->
                        kafka.start()
                        KafkaConnectContainer("kafka:19092").withNetwork(network).use { connect ->
                            connect.start()

                            val plugins = get("${connect.restUrl}/connector-plugins?connectorsOnly=false").body()
                            listOf(
                                "io.debezium.connector.postgresql.PostgresConnector",
                                "io.debezium.transforms.outbox.EventRouter",
                                "io.debezium.converters.BinaryDataConverter",
                                "org.apache.kafka.connect.transforms.Filter",
                                "io.apicurio.registry.utils.converter.AvroConverter",
                            ).filter { it !in plugins } shouldBe emptyList()
                            KafkaConnectContainer.infraDirectory
                                .resolve("kafka-connect")
                                .toFile()
                                .list()!!
                                .toList() shouldContainAll
                                listOf("order-outbox.json")
                        }
                    }
            }
        }
    })
