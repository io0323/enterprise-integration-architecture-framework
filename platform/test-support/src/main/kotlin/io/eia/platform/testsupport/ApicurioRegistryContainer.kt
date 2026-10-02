package io.eia.platform.testsupport

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import java.time.Duration

/**
 * Schema Registry(Apicurio Registry 3)のテスト用コンテナ。イメージは images.env の `APICURIO_REGISTRY_IMAGE`。
 * compose と同じく、グローバルの互換性ルールを FULL_TRANSITIVE、妥当性ルールを FULL にする(ADR-0014・ADR-0016 §7)。
 * ストレージはメモリ(既定)。コンテナを作り直すと登録は消える。
 *
 * 起動の完了は、compose のヘルスチェックと同じ管理用のポートの `/health/ready` と、REST の `/system/info` の両方で判定する
 * (どちらか一方だけでは、もう一方の準備が終わる前に要求して、接続の拒否やタイムアウトになることがある。
 * ほかの統合テストと並列に動くと起動が遅くなるため、待ちは長めにする)。
 */
public class ApicurioRegistryContainer : GenericContainer<ApicurioRegistryContainer>(InfraImages.get("APICURIO_REGISTRY_IMAGE")) {
    init {
        withEnv("APICURIO_RULES_GLOBAL_COMPATIBILITY", "FULL_TRANSITIVE")
        withEnv("APICURIO_RULES_GLOBAL_VALIDITY", "FULL")
        // compose と同じメモリの上限(mem_limit 640m・ヒープはその 60%)。上限がないと JVM がホストのメモリから既定のヒープを決め、
        // ほかの統合テストのコンテナと並列に動いたときに Docker のメモリを使い切って落ちる
        withEnv("JAVA_OPTS_APPEND", "-XX:MaxRAMPercentage=60")
        withCreateContainerCmdModifier { it.hostConfig?.withMemory(MEMORY_LIMIT_BYTES) }
        withExposedPorts(HTTP_PORT, MANAGEMENT_PORT)
        // 管理用のポートの準備が先に終わり、REST のポートがまだ接続を受けないことがあるため、両方を待つ
        waitingFor(
            WaitAllStrategy()
                .withStrategy(Wait.forHttp("/health/ready").forPort(MANAGEMENT_PORT))
                .withStrategy(Wait.forHttp("$API_PATH/system/info").forPort(HTTP_PORT))
                .withStartupTimeout(STARTUP_TIMEOUT),
        )
    }

    /** ホストから見た REST API(v3)のベース URL。 */
    public val baseUrl: String get() = "http://$host:${getMappedPort(HTTP_PORT)}$API_PATH"

    /** 同じ Docker ネットワークのコンテナから見たベース URL([alias] はネットワークのエイリアス)。 */
    public fun internalBaseUrl(alias: String): String = "http://$alias:$HTTP_PORT$API_PATH"

    public companion object {
        public const val HTTP_PORT: Int = 8080
        public const val MANAGEMENT_PORT: Int = 9000
        public const val API_PATH: String = "/apis/registry/v3"
        private val STARTUP_TIMEOUT: Duration = Duration.ofMinutes(3)
        private const val MEMORY_LIMIT_BYTES: Long = 640L * 1024 * 1024
    }
}
