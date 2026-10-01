package io.eia.tools.auditverify

import io.eia.platform.audit.AuditError
import io.eia.platform.audit.AuditStorageUnavailable
import io.eia.platform.audit.anchor.Anchor
import io.eia.platform.audit.anchor.AnchorStore
import io.eia.platform.audit.anchor.AnchorVersion
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration
import java.time.Instant

private val ENV =
    mapOf(
        "AUDIT_SERVICE" to "order",
        "AUDIT_JDBC_URL" to "jdbc:postgresql://localhost:19432/order_service",
        "AUDIT_DB_USER" to "order_service_app",
    )
private val SECRETS = EnvSecretProvider(mapOf("AUDIT_DB_PASSWORD" to "db-password-value"))
private val CREATED: Instant = Instant.parse("2026-09-28T01:00:00Z")

private inline fun <reified T> proxy(crossinline handler: (String, Array<Any?>) -> Any?): T =
    Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, args -> handler(method.name, args ?: emptyArray()) } as T

/** 1 列の結果([values] の行数)。 */
private fun resultSet(values: List<Long>): ResultSet {
    var index = -1
    return proxy { method, _ ->
        when (method) {
            "next" -> ++index < values.size
            "getLong" -> values[index]
            else -> Unit
        }
    }
}

/** 接続の設定の問い合わせ(自動コミット・分離レベル・読み取り専用)に、新しい接続と同じ値を返す。それ以外は何もしない。 */
private fun connectionDefaults(method: String): Any? =
    when (method) {
        "getAutoCommit" -> true
        "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED
        "isReadOnly" -> false
        else -> Unit
    }

/** 監査テーブルが空の DB(件数は 0、ほかの SELECT は 0 行)。 */
private fun emptyDatabase(): Connection =
    proxy { method, args ->
        if (method == "prepareStatement") {
            val rows = if ((args[0] as String).startsWith("SELECT count(*)")) resultSet(listOf(0L)) else resultSet(emptyList())
            proxy<PreparedStatement> { name, _ -> if (name == "executeQuery") rows else Unit }
        } else {
            connectionDefaults(method)
        }
    }

/** SQL の実行で [sqlState] の例外を投げる DB。 */
private fun failingDatabase(sqlState: String): Connection {
    val statement = proxy<PreparedStatement> { name, _ -> if (name == "executeQuery") throw SQLException("失敗", sqlState) else Unit }
    return proxy { name, _ -> if (name == "prepareStatement") statement else connectionDefaults(name) }
}

private class FixedStore(
    private val result: Result<List<AnchorVersion>, AuditError>,
) : AnchorStore {
    override fun put(
        key: String,
        body: ByteArray,
        retainUntil: Instant,
    ): Result<String, AuditError> = throw UnsupportedOperationException()

    override fun listVersions(prefix: String): Result<List<AnchorVersion>, AuditError> = result

    override fun latest(prefix: String): Result<AnchorVersion?, AuditError> = throw UnsupportedOperationException()
}

private fun anchorAt(seq: Long): AnchorVersion =
    AnchorVersion(
        key = "anchors/order/2026-09-28.json",
        versionId = "v1",
        lastModified = CREATED,
        isDeleteMarker = false,
        body = Anchor(service = "order", seq = seq, hash = "0".repeat(64), canonicalVersion = 1, createdAt = CREATED.toString()).toJson(),
        retentionMode = "COMPLIANCE",
        retainUntil = CREATED.plus(Duration.ofDays(2)),
    )

private fun run(
    env: Map<String, String> = ENV,
    store: AnchorStore = FixedStore(ok(emptyList())),
    connect: (String, String, String) -> Connection = { _, _, _ -> emptyDatabase() },
): Pair<Int, String> {
    val buffer = ByteArrayOutputStream()
    val code = AuditVerifyCommand(SECRETS, connect, { _, _ -> store }).run(env, PrintStream(buffer, true, Charsets.UTF_8))
    return code to buffer.toString(Charsets.UTF_8)
}

class AuditVerifyCommandSpec :
    FunSpec({
        test("改竄の疑いがなければ終了コード 0") {
            val (code, output) = run()
            code shouldBe AuditVerifyCommand.INTACT
            output shouldContain "OK: 改竄の疑いはありません"
            output shouldContain "注意: アンカーがありません"
        }

        test("改竄の疑いがあれば終了コード 1(アンカーより後の記録がない)") {
            val (code, output) = run(store = FixedStore(ok(listOf(anchorAt(3)))))
            code shouldBe AuditVerifyCommand.TAMPERED
            output shouldContain "NG anchor_record_missing"
            output shouldContain "docs/runbooks/audit-verify.md"
        }

        test("S3 や DB に接続できなければ終了コード 2。出力にパスワードを含めない") {
            run(store = FixedStore(err(AuditStorageUnavailable("S3", "HTTP 503")))).first shouldBe AuditVerifyCommand.FAILED
            val (code, output) = run(connect = { _, _, _ -> throw SQLException("password=db-password-value", "08001") })
            code shouldBe AuditVerifyCommand.FAILED
            output shouldContain "SQLSTATE 08001"
            output shouldNotContain "db-password-value"
        }

        test("監査テーブルがない・読めないときは原因を案内する") {
            val missing = run(connect = { _, _, _ -> failingDatabase("42P01") })
            missing.first shouldBe AuditVerifyCommand.FAILED
            missing.second shouldContain "AuditSchema.migrate"
            run(connect = { _, _, _ -> failingDatabase("42501") }).second shouldContain "AUDIT_DB_USER"
        }

        test("想定外の例外も終了コード 2(1 = 改竄の疑いと区別する)。例外のメッセージは出さない") {
            val buffer = ByteArrayOutputStream()
            val code =
                AuditVerifyCommand(SECRETS, { _, _, _ -> emptyDatabase() }, { _, _ -> throw IllegalStateException("secret-in-message") })
                    .run(ENV, PrintStream(buffer, true, Charsets.UTF_8))
            code shouldBe AuditVerifyCommand.FAILED
            buffer.toString(Charsets.UTF_8) shouldContain "IllegalStateException"
            buffer.toString(Charsets.UTF_8) shouldNotContain "secret-in-message"
        }

        test("DB のパスワードがなければ終了コード 2") {
            val buffer = ByteArrayOutputStream()
            AuditVerifyCommand(EnvSecretProvider(emptyMap()), { _, _, _ -> emptyDatabase() }, { _, _ -> FixedStore(ok(emptyList())) })
                .run(ENV, PrintStream(buffer)) shouldBe AuditVerifyCommand.FAILED
        }

        test("設定の誤りは終了コード 2") {
            listOf(
                ENV - "AUDIT_SERVICE",
                ENV + ("AUDIT_SERVICE" to "Order"),
                ENV - "AUDIT_JDBC_URL",
                ENV + ("AUDIT_JDBC_URL" to "jdbc:mysql://x"),
                ENV - "AUDIT_DB_USER",
                ENV + ("AUDIT_S3_ENDPOINT" to "http://bad host"),
                ENV + ("AUDIT_S3_ENDPOINT" to "localhost:19333"),
                ENV + ("AUDIT_S3_PATH_STYLE" to "yes"),
                ENV + ("AUDIT_MIN_RETENTION" to "1d"),
                ENV + ("AUDIT_MIN_RETENTION" to "-P1D"),
            ).forEach { env -> run(env = env).first shouldBe AuditVerifyCommand.FAILED }
        }

        test("設定の既定値: ローカルの S3・バケット eiaf-audit・path-style・最小の保持期間 1 日") {
            val config = (AuditVerifyConfig.from(ENV) as Result.Ok).value
            config.s3.endpoint.toString() shouldBe "http://localhost:19333"
            config.s3.bucket shouldBe "eiaf-audit"
            config.s3.pathStyle shouldBe true
            config.minRetention shouldBe Duration.ofDays(1)
            val custom =
                (
                    AuditVerifyConfig.from(
                        ENV + ("AUDIT_S3_PATH_STYLE" to "false") + ("AUDIT_MIN_RETENTION" to "PT2M"),
                    ) as Result.Ok
                ).value
            custom.s3.pathStyle shouldBe false
            custom.minRetention shouldBe Duration.ofMinutes(2)
        }
    })
