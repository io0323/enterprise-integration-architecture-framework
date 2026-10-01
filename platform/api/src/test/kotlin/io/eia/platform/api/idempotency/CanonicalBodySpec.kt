package io.eia.platform.api.idempotency

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import java.security.MessageDigest

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

class CanonicalBodySpec :
    FunSpec({
        val json = "application/json"

        test("JSON はキーの順序と空白が違っても、同じバイト列と SHA-256 になる(入れ子のオブジェクトを含む)") {
            val a = """{"b":1,"a":{"y":[1,2],"x":"v"}}""".toByteArray()
            val b = "{ \"a\" : { \"x\" : \"v\", \"y\" : [ 1, 2 ] },\n  \"b\" : 1 }".toByteArray()
            CanonicalBody.bytes(json, a).decodeToString() shouldBe """{"a":{"x":"v","y":[1,2]},"b":1}"""
            CanonicalBody.sha256Hex(json, a) shouldBe CanonicalBody.sha256Hex(json, b)
            CanonicalBody.sha256Hex(json, a) shouldMatch Regex("^[0-9a-f]{64}$")
        }

        test("値が違えば別の値になる(数値は書かれた文字列のまま。1 と 1.0 は別)") {
            CanonicalBody.sha256Hex(json, """{"q":1}""".toByteArray()) shouldNotBe
                CanonicalBody.sha256Hex(json, """{"q":1.0}""".toByteArray())
            CanonicalBody.sha256Hex(json, """{"q":1}""".toByteArray()) shouldNotBe
                CanonicalBody.sha256Hex(json, """{"q":2}""".toByteArray())
        }

        test("+json の Content-Type も JSON として正規化し、JSON でない本文と読めない JSON はバイト列のまま") {
            CanonicalBody.bytes("application/problem+json; charset=utf-8", """{ "b":1, "a":2 }""".toByteArray()).decodeToString() shouldBe
                """{"a":2,"b":1}"""
            val text = "b=1 a=2".toByteArray()
            CanonicalBody.sha256Hex("text/plain", text) shouldBe sha256(text)
            val broken = """{"a":""".toByteArray()
            CanonicalBody.sha256Hex(json, broken) shouldBe sha256(broken)
            CanonicalBody.sha256Hex(null, text) shouldBe sha256(text)
        }

        test("冪等の指紋は、この正規化をした本文から作る(本文の見た目が違うだけなら同じ指紋)") {
            val a = """{"b":1,"a":2}""".toByteArray()
            val b = """{ "a": 2, "b": 1 }""".toByteArray()
            RequestFingerprint.of("POST", "/v1/orders", json, a) shouldBe RequestFingerprint.of("POST", "/v1/orders", json, b)
        }
    })
