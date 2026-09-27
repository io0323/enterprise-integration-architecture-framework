package io.eia.platform.observability.logging

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

private const val JWT =
    "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJvcmRlci1zZXJ2aWNlIiwiYXVkIjoib3JkZXItYXBpIn0.c2lnbmF0dXJlLXZhbHVl"

class MaskingSpec :
    FunSpec({
        context("トークン") {
            test("JWT を伏せる(ヘッダの値でも本文の途中でも)") {
                Masking.mask("token=$JWT end") shouldBe "token=*** end"
                Masking.mask("received $JWT from keycloak") shouldBe "received [token] from keycloak"
            }

            test("Bearer / Basic の資格情報を伏せ、方式の名前は残す") {
                Masking.mask("Authorization: Bearer abc.def-ghi") shouldBe "Authorization: ***"
                Masking.mask("header bearer abc123 sent") shouldBe "header bearer [token] sent"
                Masking.mask("Basic dXNlcjpwYXNz") shouldBe "Basic [token]"
            }
        }

        context("秘密情報のキー") {
            test("JSON の値を伏せる(エスケープした引用符を含む値も)") {
                Masking.mask("""{"user":"u1","password":"p\"w,d","client_secret" : "s3"}""") shouldBe
                    """{"user":"u1","password":"***","client_secret" : "***"}"""
            }

            test("key=value とクエリ文字列の値を伏せる") {
                Masking.mask("grant_type=client_credentials&client_secret=s3cr3t&scope=x") shouldBe
                    "grant_type=client_credentials&client_secret=***&scope=x"
                Masking.mask("api_key: k-123, next") shouldBe "api_key: ***, next"
                Masking.mask("PASSWORD=Hunter2") shouldBe "PASSWORD=***"
            }
        }

        context("個人情報") {
            test("メールアドレス") {
                Masking.mask("customer taro.yamada+test@example.co.jp placed") shouldBe "customer [email] placed"
            }

            test("カード番号は Luhn に通るものだけを伏せる(注文番号などの数字の列は残す)") {
                Masking.mask("card 4111 1111 1111 1111 ok") shouldBe "card [card] ok"
                Masking.mask("card 4111-1111-1111-1111") shouldBe "card [card]"
                Masking.mask("order 4111111111111112") shouldBe "order 4111111111111112"
            }

            test("電話番号(E.164・区切りのある番号・国内の 10〜11 桁)") {
                Masking.mask("tel +81 90 1234 5678.") shouldBe "tel [phone]."
                Masking.mask("tel 03-1234-5678") shouldBe "tel [phone]"
                Masking.mask("tel 09012345678") shouldBe "tel [phone]"
            }
        }

        test("ID・時刻・トレースの値は変えない") {
            val safe =
                "order_id=ord-20260927-001 trace_id=4bf92f3577b34da6a3ce929d0e0e4736 at 2026-09-27T12:00:00Z took 123ms status=200"
            Masking.mask(safe) shouldBe safe
            Masking.mask("") shouldBe ""
        }

        test("機密のヘッダは値を丸ごと伏せ、ほかのヘッダは mask を通す") {
            Masking.maskHeader("Authorization", "Bearer x") shouldBe "***"
            Masking.maskHeader("Cookie", "session=abc") shouldBe "***"
            Masking.maskHeader("X-Request-From", "a@example.com") shouldBe "[email]"
        }

        test("複数の種類が混ざった文字列から、どれも漏らさない") {
            val masked = Masking.mask("""login {"email":"a@example.com","password":"pw1"} Bearer $JWT card=4111111111111111""")

            listOf("a@example.com", "pw1", JWT, "4111111111111111").forEach { masked shouldNotContain it }
        }
    })
