package io.eia.shared.sdk.http.token

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FormEncodingSpec :
    FunSpec({
        test("英数字と . - * _ はそのまま、空白は +、ほかは UTF-8 のバイトごとに %XX(JVM の URLEncoder と同じ)") {
            FormEncoding.encode("aZ09.-*_") shouldBe "aZ09.-*_"
            FormEncoding.encode("a b") shouldBe "a+b"
            FormEncoding.encode(":+/%~") shouldBe "%3A%2B%2F%25%7E"
            FormEncoding.encode("秘密") shouldBe "%E7%A7%98%E5%AF%86"
        }
    })
