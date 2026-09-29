package io.eia.tools.auditverify

import io.eia.platform.security.secret.EnvSecretProvider
import kotlin.system.exitProcess

fun main() {
    exitProcess(AuditVerifyCommand(EnvSecretProvider()).run(System.getenv(), System.out))
}
