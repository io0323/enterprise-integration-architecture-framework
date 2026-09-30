package io.eia.platform.observability

// 違反: observability → security は許可していない(security/build.gradle.kts の依存と合わせて循環にもなる)
import io.eia.platform.security.secret.SecretProvider

class DependsOnSecurity(
    val secrets: SecretProvider,
)
