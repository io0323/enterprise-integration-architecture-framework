package io.eia.platform.observability

import io.eia.platform.security.secret.SecretProvider

// 準拠: テストのソースセットの参照は、platform 間の依存の規則の対象外
class UsesSecurityInTest(
    val secrets: SecretProvider,
)
