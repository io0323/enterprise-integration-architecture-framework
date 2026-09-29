package io.eia.platform.testsupport

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import java.security.SecureRandom
import java.time.Duration
import java.util.HexFormat

/**
 * S3 互換ストレージ(SeaweedFS。ADR-0015)のテスト用コンテナ。イメージは images.env の `SEAWEEDFS_IMAGE`。
 * compose(infra/local/docker-compose.yml)と同じく `weed server -s3` の 1 プロセスで動かす。
 *
 * [identities] ごとにアクセスキーとシークレットを乱数で作る。アクションの書き方は compose の `s3.json` と同じ(例 `Read:eiaf-audit`)。
 */
public class SeaweedFsContainer(
    identities: Map<String, List<String>>,
) : GenericContainer<SeaweedFsContainer>(InfraImages.get("SEAWEEDFS_IMAGE")) {
    /** identity の名前ごとの資格情報。 */
    public val credentials: Map<String, S3Credentials> =
        identities.keys.associateWith { S3Credentials(randomHex(), randomHex()) }

    init {
        val json = s3Config(identities, credentials)
        withCopyToContainer(Transferable.of(json), CONFIG_PATH)
        withCommand(
            "server",
            "-dir=/data",
            "-ip.bind=0.0.0.0",
            "-master.volumeSizeLimitMB=64",
            "-volume.max=8",
            "-s3",
            "-s3.port=$S3_PORT",
            "-s3.config=$CONFIG_PATH",
        )
        withExposedPorts(S3_PORT)
        waitingFor(Wait.forHttp("/healthz").forPort(S3_PORT).withStartupTimeout(STARTUP_TIMEOUT))
    }

    /** ホストから見た S3 のエンドポイント(path-style で使う)。 */
    public val endpoint: String get() = "http://$host:${getMappedPort(S3_PORT)}"

    public data class S3Credentials(
        val accessKey: String,
        val secretKey: String,
    ) {
        override fun toString(): String = "S3Credentials(accessKey=$accessKey, secretKey=***)"
    }

    internal companion object {
        const val S3_PORT = 8333
        const val CONFIG_PATH = "/etc/seaweedfs/s3.json"
        const val SECRET_BYTES = 16
        val STARTUP_TIMEOUT: Duration = Duration.ofMinutes(2)

        /** SeaweedFS の `-s3.config` の JSON(compose の `s3.json` と同じ形)。 */
        fun s3Config(
            identities: Map<String, List<String>>,
            credentials: Map<String, S3Credentials>,
        ): String =
            identities.entries.joinToString(",", prefix = """{"identities":[""", postfix = "]}") { (name, actions) ->
                val credential = credentials.getValue(name)
                """{"name":"$name","credentials":[{"accessKey":"${credential.accessKey}","secretKey":"${credential.secretKey}"}],""" +
                    """"actions":[${actions.joinToString(",") { "\"$it\"" }}]}"""
            }

        fun randomHex(): String = HexFormat.of().formatHex(ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes))
    }
}
