package io.eia.platform.api.idempotency

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private fun fp(
    body: String,
    method: String = "POST",
    path: String = "/v1/orders",
    contentType: String? = "application/json",
): RequestFingerprint = RequestFingerprint.of(method, path, contentType, body.toByteArray())

class RequestFingerprintSpec :
    FunSpec({
        test("SHA-256 の 16 進(64 文字)") {
            fp("{}").value.matches(Regex("[0-9a-f]{64}")) shouldBe true
        }

        test("JSON はキーの順序(入れ子を含む)と空白によらず同じ指紋") {
            fp("""{"a":1,"b":{"x":[1,{"q":2,"p":1}],"y":true}}""") shouldBe
                fp(
                    """
                    { "b" : { "y": true, "x": [ 1, { "p": 1, "q": 2 } ] },
                      "a": 1 }
                    """,
                )
        }

        test("JSON の値・配列の順序が違えば別の指紋。数値は書かれた文字列のまま(1 と 1.0 は別)") {
            fp("""{"quantity":1}""") shouldNotBe fp("""{"quantity":2}""")
            fp("""{"lines":[1,2]}""") shouldNotBe fp("""{"lines":[2,1]}""")
            fp("""{"quantity":1}""") shouldNotBe fp("""{"quantity":1.0}""")
        }

        test("+json と charset 付きの Content-Type も JSON として正規化する") {
            fp("""{"a":1,"b":2}""", contentType = "application/merge-patch+json") shouldBe
                fp("""{"b":2,"a":1}""", contentType = "application/merge-patch+json")
            fp("""{"a":1,"b":2}""", contentType = "application/json; charset=utf-8") shouldBe
                fp("""{"b":2, "a":1}""", contentType = "application/json")
        }

        test("メソッド・パス・クエリが違えば別の指紋") {
            fp("{}") shouldNotBe fp("{}", method = "PUT")
            fp("{}") shouldNotBe fp("{}", path = "/v1/orders/1")
            fp("{}") shouldNotBe fp("{}", path = "/v1/orders?dryRun=true")
        }

        test("JSON でない本文と、JSON として読めない本文は、バイト列のまま使う") {
            fp("a=1&b=2", contentType = "application/x-www-form-urlencoded") shouldNotBe
                fp("b=2&a=1", contentType = "application/x-www-form-urlencoded")
            fp("""{"a":1, "b":""", contentType = "application/json") shouldNotBe fp("""{"a":1,"b":""", contentType = "application/json")
            RequestFingerprint.of("POST", "/v1/orders", "application/json", byteArrayOf(0xC3.toByte(), 0x28)) shouldBe
                RequestFingerprint.of("POST", "/v1/orders", "application/octet-stream", byteArrayOf(0xC3.toByte(), 0x28))
        }

        test("区切りで境界をずらした入力は、同じ指紋にならない") {
            fp("x", method = "POST", path = "/a") shouldNotBe fp("x", method = "POST/", path = "a")
        }
    })
