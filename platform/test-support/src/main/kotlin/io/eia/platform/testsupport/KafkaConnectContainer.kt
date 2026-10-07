package io.eia.platform.testsupport

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Kafka Connect(Debezium の Postgres コネクタ + Apicurio の Converter)のテスト用コンテナ。
 * イメージは compose と同じ `infra/local/images/kafka-connect/Dockerfile` から組み立て、環境変数も compose の `kafka-connect` と揃える
 * (ADR-0016 §9)。秘密情報はコネクタの設定に `${env:VAR}` で書き、[secrets] の環境変数で渡す。
 *
 * イメージは docker の CLI(BuildKit)で組み立てる。Dockerfile が `ADD --checksum` と `COPY --chmod` を使い、Testcontainers の
 * `ImageFromDockerfile`(BuildKit を使わない)では組み立てられないため。同じ JVM の中では 1 回だけ組み立てる。
 *
 * @param bootstrapServers 同じネットワークのブローカー(例 `kafka:19092`)
 */
public class KafkaConnectContainer(
    bootstrapServers: String,
    secrets: Map<String, String> = emptyMap(),
    offsetFlushInterval: Duration = Duration.ofSeconds(1),
) : GenericContainer<KafkaConnectContainer>(image()) {
    init {
        withEnv(
            mapOf(
                "CONNECT_BOOTSTRAP_SERVERS" to bootstrapServers,
                "CONNECT_GROUP_ID" to "debezium.connect",
                "CONNECT_CONFIG_STORAGE_TOPIC" to "_connect.configs",
                "CONNECT_OFFSET_STORAGE_TOPIC" to "_connect.offsets",
                "CONNECT_STATUS_STORAGE_TOPIC" to "_connect.status",
                "CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR" to "1",
                "CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR" to "1",
                "CONNECT_STATUS_STORAGE_REPLICATION_FACTOR" to "1",
                "CONNECT_TOPIC_CREATION_ENABLE" to "true",
                // オフセットの確定(Postgres では slot の進み)の間隔。テストの待ち時間を減らすため、ローカル基盤(既定 60 秒)より短くする
                "CONNECT_OFFSET_FLUSH_INTERVAL_MS" to offsetFlushInterval.toMillis().toString(),
                "CONNECT_CONFIG_PROVIDERS" to "env",
                "CONNECT_CONFIG_PROVIDERS_ENV_CLASS" to "org.apache.kafka.common.config.provider.EnvVarConfigProvider",
                "KAFKA_HEAP_OPTS" to "-Xms256m -Xmx384m",
            ) + secrets,
        )
        withExposedPorts(REST_PORT)
        waitingFor(Wait.forHttp("/connector-plugins").forPort(REST_PORT).withStartupTimeout(STARTUP_TIMEOUT))
    }

    /** ホストから見た REST API のベース URL。 */
    public val restUrl: String get() = "http://$host:${getMappedPort(REST_PORT)}"

    public companion object {
        public const val REST_PORT: Int = 8083
        private const val IMAGE_TAG = "eiaf-kafka-connect-it:latest"
        private val STARTUP_TIMEOUT: Duration = Duration.ofMinutes(4)
        private const val BUILD_TIMEOUT_MINUTES = 10L

        /** `infra/local`(images.env のあるディレクトリ)。 */
        public val infraDirectory: Path by lazy {
            Path.of(checkNotNull(System.getProperty(InfraImages.FILE_PROPERTY)) { "Gradle の Test タスクから実行してください" }).parent
        }

        private val built: DockerImageName by lazy { build() }

        private fun image(): DockerImageName = built

        private fun build(): DockerImageName {
            val context = infraDirectory.resolve("images/kafka-connect")
            check(Files.isDirectory(context)) { "$context がありません" }
            val process =
                ProcessBuilder(
                    "docker",
                    "build",
                    "--build-arg",
                    "KAFKA_IMAGE=${InfraImages.get("KAFKA_IMAGE").asCanonicalNameString()}",
                    "-t",
                    IMAGE_TAG,
                    context.toString(),
                ).redirectErrorStream(true)
                    .apply { environment()["DOCKER_BUILDKIT"] = "1" }
                    .start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES) && process.exitValue() == 0) {
                "Kafka Connect のイメージを組み立てられません:\n${output.takeLast(OUTPUT_TAIL)}"
            }
            return DockerImageName.parse(IMAGE_TAG)
        }

        private const val OUTPUT_TAIL = 4_000
    }
}
