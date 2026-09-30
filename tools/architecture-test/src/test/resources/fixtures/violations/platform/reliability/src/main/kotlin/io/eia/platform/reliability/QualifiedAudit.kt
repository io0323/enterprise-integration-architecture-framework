package io.eia.platform.reliability

// 違反: import せずに完全修飾名で reliability → audit を参照する
class QualifiedAudit(
    val event: io.eia.platform.audit.AuditEvent,
)
