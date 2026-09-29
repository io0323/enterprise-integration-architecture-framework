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

private inline fun <reified T> proxy(crossinline handler: (String) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> handler(method.name) } as T

/** 監査テーブルが空の DB(どの SELECT も 0 行)。 */
private fun emptyDatabase(): Connection {
    val rows = proxy<ResultSet> { if (it == "next") false else Unit }
    val statement = proxy<PreparedStatement> { if (it == "executeQuery") rows else Unit }
    return proxy { if (it == "prepareStatement") statement else Unit }
}

/** SQL の実行で [sqlState] の例外を投げる DB。 */
private fun failingDatabase(sqlState: String): Connection {
    val statement = proxy<PreparedStatement> { if (it == "executeQuery") throw SQLException("失敗", sqlState) else Unit }
    return proxy { if (it == "prepareStatement") statement else Unit }
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
