package io.eia.tools.schemapublish

import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.ContentId
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.SchemaRegistryError
import io.eia.platform.schemaregistry.SchemaRejected
import io.eia.platform.schemaregistry.SchemaSubject
import io.eia.shared.kernel.Jitter
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryDecision
import io.eia.shared.kernel.RetryPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val REQUEST_TIMEOUT = 30.seconds

private const val USAGE = "usage: schema-publish --registry <base url(…/apis/registry/v3)> [--root <repository root>]"

/** 終了コード。0 = すべて登録済み / 1 = レジストリが拒否した(互換性の違反など)/ 2 = 実行できない(接続できない・引数の誤り)。 */
internal object ExitCode {
    const val OK = 0
    const val REJECTED = 1
    const val CANNOT_RUN = 2
}

internal data class Options(
    val registry: String,
    val root: Path,
) {
    companion object {
        /** 解析できなければ null(引数の数が奇数・未知の引数・--registry がない)。 */
        fun parse(args: List<String>): Options? {
            val pairs = args.chunked(2).associate { it.first() to it.getOrNull(1) }
            val valid = args.size % 2 == 0 && pairs.keys.all { it in setOf("--registry", "--root") }
            val registry = pairs["--registry"]
            return if (valid && registry != null) Options(registry, Path(pairs["--root"] ?: ".")) else null
        }
    }
}

/**
 * レジストリの一時的な失敗(接続できない・5xx)のリトライ。起動の直後のレジストリは、ヘルスチェック(管理用のポート)が UP でも、
 * REST のポートがまだ接続を受けないことがある(compose の schema-publish が最初の要求で失敗した)。合計でおよそ 30 秒まで待つ。
 * 拒否(互換性の違反など。NonRetryable)はリトライしない。
 */
internal val RETRY = RetryPolicy(initialDelay = 1.seconds, multiplier = 2.0, maxAttempts = 6, maxDelay = 8.seconds, jitter = Jitter.NONE)

/** 契約のスキーマを登録し、トピックごとの結果を表示する。 */
internal suspend fun publish(
    subjects: List<SchemaSubject>,
    client: ApicurioRegistryClient,
    out: (String) -> Unit,
    retry: RetryPolicy = RETRY,
    sleep: suspend (Duration) -> Unit = { delay(it) },
): Int {
    val failures = mutableListOf<SchemaRegistryError>()
    subjects.forEach { subject ->
        when (val result = registerWithRetry(subject, client, retry, sleep, out)) {
            is Result.Ok -> {
                out("OK   ${subject.artifactId} contentId=${result.value}")
            }

            is Result.Err -> {
                failures += result.error
                out("FAIL ${subject.artifactId} ${result.error.message}")
            }
        }
    }
    return when {
        failures.isEmpty() -> ExitCode.OK
        failures.all { it is SchemaRejected } -> ExitCode.REJECTED
        else -> ExitCode.CANNOT_RUN
    }
}

private suspend fun registerWithRetry(
    subject: SchemaSubject,
    client: ApicurioRegistryClient,
    retry: RetryPolicy,
    sleep: suspend (Duration) -> Unit,
    out: (String) -> Unit,
): Result<ContentId, SchemaRegistryError> {
    var attempt = 1
    while (true) {
        val result = client.register(subject)
        val error = (result as? Result.Err)?.error ?: return result
        val decision = retry.decide(attempt, error.asDomainError(), Random.Default)
        if (decision !is RetryDecision.Retry) return result
        out("WAIT ${subject.artifactId} ${error.message}(${decision.delay} 後に再試行。$attempt 回目)")
        sleep(decision.delay)
        attempt++
    }
}

internal fun run(args: List<String>): Int {
    val options = Options.parse(args)
    if (options == null) {
        System.err.println(USAGE)
        return ExitCode.CANNOT_RUN
    }
    val subjects = ContractSchemas.load(options.root.resolve("contracts"))
    println("${subjects.size} 件のスキーマを ${options.registry} に登録します")
    return HttpClient(CIO)
        .use { http ->
            // 1 回だけ実行する CLI なので、起動直後で応答の遅いレジストリも待てるよう、1 回の要求の上限を長めにする
            val client = ApicurioRegistryClient(SchemaRegistryConfig(options.registry, requestTimeout = REQUEST_TIMEOUT), http)
            runBlocking { publish(subjects, client, ::println) }
        }.also { code ->
            if (code == ExitCode.CANNOT_RUN) System.err.println("Schema Registry に接続できないか、応答が不正です(make up で起動しているか確認してください)")
        }
}

fun main(args: Array<String>) {
    exitProcess(run(args.toList()))
}
