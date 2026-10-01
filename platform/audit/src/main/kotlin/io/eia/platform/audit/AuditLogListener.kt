package io.eia.platform.audit

import java.time.Duration

/**
 * 監査の追記([io.eia.platform.audit.jdbc.AuditLog.append])の結果を受け取る。メトリクス([AuditMetrics])に使う(ADR-0017 §8 の A17-5)。
 * 記録の中身(details の値など)は渡さない。
 */
public interface AuditLogListener {
    /**
     * 追記できた。
     *
     * @param duration 追記の全体の所要時間(正規化・ロックの待ち・INSERT)
     * @param lockWait チェーンのロック(`pg_advisory_xact_lock`)を取るまでの待ち時間。ほかの追記が同時に多いと長くなる
     */
    public fun appended(
        duration: Duration,
        lockWait: Duration,
    )

    /** 追記できなかった(業務のトランザクションは取り消される)。 */
    public fun failed(error: AuditError)

    public companion object {
        /** 何もしない。 */
        public val NONE: AuditLogListener =
            object : AuditLogListener {
                override fun appended(
                    duration: Duration,
                    lockWait: Duration,
                ): Unit = Unit

                override fun failed(error: AuditError): Unit = Unit
            }
    }
}
