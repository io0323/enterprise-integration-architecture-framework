package io.eia.shared.kernel

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ResultSpec :
    FunSpec({
        val success: Result<Int, String> = ok(2)
        val failure: Result<Int, String> = err("boom")

        test("isOk / isErr と値の取り出し") {
            success.isOk shouldBe true
            success.isErr shouldBe false
            failure.isErr shouldBe true
            success.getOrNull() shouldBe 2
            failure.getOrNull().shouldBeNull()
            failure.errorOrNull() shouldBe "boom"
            success.errorOrNull().shouldBeNull()
            failure.getOrElse { it.length } shouldBe 4
        }

        test("map / mapError は該当する側だけを変換する") {
            success.map { it * 10 } shouldBe ok(20)
            failure.map { it * 10 } shouldBe err("boom")
            failure.mapError { it.uppercase() } shouldBe err("BOOM")
            success.mapError { it.uppercase() } shouldBe ok(2)
        }

        test("flatMap は失敗で短絡する") {
            success.flatMap { if (it > 1) ok(it + 1) else err("small") } shouldBe ok(3)
            success.flatMap { err("next") } shouldBe err("next")
            failure.flatMap { ok(it + 1) } shouldBe err("boom")
        }

        test("recover は失敗を置き換える") {
            failure.recover { ok(0) } shouldBe ok(0)
            failure.recover { err(it.length) } shouldBe err(4)
            success.recover { ok(0) } shouldBe ok(2)
        }

        test("onOk / onErr は該当する側だけで実行される") {
            val seen = mutableListOf<String>()
            success.onOk { seen += "ok:$it" }.onErr { seen += "err:$it" }
            failure.onOk { seen += "ok:$it" }.onErr { seen += "err:$it" }
            seen shouldBe listOf("ok:2", "err:boom")
        }

        test("zip は両方成功なら合成し、失敗なら最初の失敗を返す") {
            success.zip(ok(3)) { a, b -> a * b } shouldBe ok(6)
            success.zip(err("second")) { a: Int, b: Int -> a * b } shouldBe err("second")
            failure.zip(err("second")) { a: Int, b: Int -> a * b } shouldBe err("boom")
        }

        test("combine は全成功ならリストを、失敗があれば最初の失敗を返す") {
            listOf(ok(1), ok(2)).combine() shouldBe ok(listOf(1, 2))
            listOf<Result<Int, String>>(ok(1), err("a"), err("b")).combine() shouldBe err("a")
            emptyList<Result<Int, String>>().combine() shouldBe ok(emptyList())
        }
    })
