package io.eia.platform.security

import io.eia.platform.reliability.HttpCallClassifier

// 準拠: security → reliability は許可している
class UsesReliability(
    val classifier: HttpCallClassifier,
)
