package io.eia.tools.auditverify

import io.eia.platform.audit.anchor.AnchorStore
import io.eia.platform.audit.anchor.S3AnchorStore
import io.eia.platform.audit.verify.AuditVerification
import io.eia.platform.audit.verify.VerificationReport
import io.eia.platform.security.secret.SecretProvider
import io.eia.shared.kernel.Result
import java.io.PrintStream
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * 監査記録の改竄を検査する(`make audit-verify SERVICE=<name>`。ADR-0017)。
 *
 * 終了コード:
 * - [INTACT] 0: 改竄の疑いはない
 * - [TAMPERED] 1: 改竄の疑いがある(チェーンの不整合・アンカーとの不一致・アンカーの保持の設定の不備)
 * - [FAILED] 2: 検査を実行できなかった(設定の誤り・DB や S3 に接続できない)
 *
 * 出力には seq とアンカーのキーだけを出し、記録の中身と資格情報は出さない。
 */
internal class AuditVerifyCommand(
    private val secrets: SecretProvider,
    private val connect: (url: String, user: String, password: String) -> Connection = DriverManager::getConnection,
    private val storeFactory: (
        AuditVerifyConfig,
        SecretProvider,
    ) -> AnchorStore = { config, provider -> S3AnchorStore(config.s3, provider) },
) {
    /**
     * 検査して終了コードを返す。想定外の例外も [FAILED] にする(JVM が例外で終わると終了コードが 1 になり、
     * 「改竄の疑い」と区別できなくなるため)。例外のメッセージは値を含みうるので、クラス名だけを出す。
     */
    @Suppress("TooGenericExceptionCaught") // 境界で全例外を終了コード 2 に変換するのがこの関数の責務
    fun run(
        env: Map<String, String>,
        out: PrintStream,
    ): Int =
        try {
            verify(env, out)
        } catch (e: Exception) {
            failed(out, "想定外のエラー(${e::class.simpleName})")
        }

    @Suppress("ReturnCount") // 設定・資格情報・検査のそれぞれの失敗で返す
    private fun verify(
        env: Map<String, String>,
        out: PrintStream,
    ): Int {
        val config =
            when (val parsed = AuditVerifyConfig.from(env)) {
                is Result.Ok -> parsed.value
                is Result.Err -> return failed(out, "設定: ${parsed.error}")
            }
        val password =
            when (val secret = secrets.get(AuditVerifyConfig.DB_PASSWORD)) {
                is Result.Ok -> secret.value.reveal()
                is Result.Err -> return failed(out, secret.error.message)
            }
        val store = storeFactory(config, secrets)
        return try {
            connect(config.jdbcUrl, config.dbUser, password).use { connection ->
                connection.isReadOnly = true
                when (val result = AuditVerification(config.service, store, config.minRetention).run(connection)) {
                    is Result.Ok -> report(out, result.value)
                    is Result.Err -> failed(out, result.error.message + hint(result.error.message))
                }
            }
        } catch (e: SQLException) {
            // ドライバのメッセージは接続先や値を含みうるため、SQLSTATE だけを出す
            failed(out, "PostgreSQL に接続できません(SQLSTATE ${e.sqlState ?: "なし"})")
        } finally {
            (store as? AutoCloseable)?.close()
        }
    }

    private fun report(
        out: PrintStream,
        report: VerificationReport,
    ): Int {
        out.println(
            "audit-verify: service=${report.service} records=${report.recordCount} head_seq=${report.headSeq ?: "-"} " +
                "anchor_versions=${report.anchorVersionCount}",
        )
        if (report.anchorVersionCount == 0) out.println("注意: アンカーがありません(末尾の記録の改竄と削除は検出できません)")
        if (report.intact) {
            out.println("OK: 改竄の疑いはありません")
            return INTACT
        }
        report.findings.forEach { out.println("NG ${it.code}: ${it.describe()}") }
        out.println("NG: 改竄の疑いが ${report.findings.size} 件あります(対応: docs/runbooks/audit-verify.md)")
        return TAMPERED
    }

    /** よくある原因の案内。42P01 は表がない(サービスのマイグレーションで AuditSchema.migrate を実行していない)。 */
    private fun hint(message: String): String =
        when {
            message.contains("SQLSTATE 42P01") -> "(監査テーブルがありません。サービスのマイグレーションで AuditSchema.migrate を実行してください)"
            message.contains("SQLSTATE 42501") -> "(監査テーブルを読む権限がありません。AUDIT_DB_USER を確かめてください)"
            else -> ""
        }

    private fun failed(
        out: PrintStream,
        message: String,
    ): Int {
        out.println("ERROR: 検査を実行できません: $message")
        return FAILED
    }

    companion object {
        const val INTACT = 0
        const val TAMPERED = 1
        const val FAILED = 2
    }
}
