package io.eia.platform.audit.jdbc

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.getOrNull
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class AuditLogReaderSpec :
    FunSpec({
        test("details の JSON の読み取り: 文字列と null だけを受け付ける") {
            AuditLogReader.parseDetails("""{"a":"x","b":null}""").getOrNull() shouldBe mapOf("a" to "x", "b" to null)
            AuditLogReader.parseDetails("""{"a":5}""").shouldBeInstanceOf<Result.Err<String>>()
            AuditLogReader.parseDetails("""[1]""").shouldBeInstanceOf<Result.Err<String>>()
            AuditLogReader.parseDetails("{").shouldBeInstanceOf<Result.Err<String>>()
            AuditLogReader.parseDetails(null).shouldBeInstanceOf<Result.Err<String>>()
        }
    })
