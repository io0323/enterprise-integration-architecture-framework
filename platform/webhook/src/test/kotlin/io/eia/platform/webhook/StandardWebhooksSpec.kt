package io.eia.platform.webhook

import io.eia.platform.security.secret.Secret
import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private fun <T, E> Result<T, E>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private fun <T, E> Result<T, E>.error(): E =
    when (this) {
        is Result.Ok -> fail("Err を期待しましたが Ok でした: $value")
        is Result.Err -> error
    }

class StandardWebhooksSpec :
    FunSpec({
        val current = Secret("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw")
        val previous = Secret("previous-secret-value")
        val verifier = WebhookVerifier()
        val signedAt = Instant.fromEpochSeconds(1614265330)
        val body = """{"test": 2432232314}""".toByteArray()
        val id = "msg_p5jXN8AQM9LWM0D4loKWxJek"

        test("Standard Webhooks の公開の例と同じ署名になる(whsec_ の鍵は base64 を解く)") {
            StandardWebhooks.signatureHeader(listOf(current), id, signedAt, body) shouldBe "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE="
        }

        test("署名と時刻が正しければ受け付ける。一致した鍵の位置を返す") {
            val header = StandardWebhooks.signatureHeader(listOf(current), id, signedAt, body)
            verifier
                .verify(
                    WebhookHeaders(id, signedAt.epochSeconds.toString(), header),
                    body,
                    listOf(current, previous),
                    signedAt,
                ).ok() shouldBe
                VerifiedWebhook(id, signedAt, 0)
        }

        test("鍵の差し替え: 差し替え前の鍵の署名も受け付ける。送り手が新旧の両方で署名しても受け付ける") {
            val old = StandardWebhooks.signatureHeader(listOf(previous), id, signedAt, body)
            verifier
                .verify(
                    WebhookHeaders(id, signedAt.epochSeconds.toString(), old),
                    body,
                    listOf(current, previous),
                    signedAt,
                ).ok()
                .matchedSecret shouldBe
                1
            val both = StandardWebhooks.signatureHeader(listOf(current, previous), id, signedAt, body)
            both.split(' ').size shouldBe 2
            verifier
                .verify(
                    WebhookHeaders(id, signedAt.epochSeconds.toString(), both),
                    body,
                    listOf(current),
                    signedAt,
                ).ok()
                .matchedSecret shouldBe
                0
            // 差し替えを終えて古い鍵を外した後は、古い鍵だけの署名は受け付けない
            verifier.verify(WebhookHeaders(id, signedAt.epochSeconds.toString(), old), body, listOf(current), signedAt).error() shouldBe
                WebhookRejection.SignatureMismatch
        }

        test("本文・ID・時刻のどれかを書き換えると署名が一致しない(生のバイト列で検証する。空白 1 つの違いでも)") {
            val header = StandardWebhooks.signatureHeader(listOf(current), id, signedAt, body)
            val reformatted = """{"test":2432232314}""".toByteArray()
            verifier
                .verify(
                    WebhookHeaders(id, signedAt.epochSeconds.toString(), header),
                    reformatted,
                    listOf(current),
                    signedAt,
                ).error() shouldBe
                WebhookRejection.SignatureMismatch
            verifier
                .verify(
                    WebhookHeaders("msg_other", signedAt.epochSeconds.toString(), header),
                    body,
                    listOf(current),
                    signedAt,
                ).error() shouldBe
                WebhookRejection.SignatureMismatch
            val later = signedAt + 1.seconds
            verifier.verify(WebhookHeaders(id, later.epochSeconds.toString(), header), body, listOf(current), later).error() shouldBe
                WebhookRejection.SignatureMismatch
            verifier.verify(WebhookHeaders(id, signedAt.epochSeconds.toString(), header), body, listOf(previous), signedAt).error() shouldBe
                WebhookRejection.SignatureMismatch
        }

        test("リプレイ: 署名の時刻が前後 5 分を外れたら拒否する。5 分ちょうどまでは受け付ける") {
            val header = StandardWebhooks.signatureHeader(listOf(current), id, signedAt, body)
            val stamp = signedAt.epochSeconds.toString()
            verifier.verify(WebhookHeaders(id, stamp, header), body, listOf(current), signedAt + 5.minutes).ok()
            verifier.verify(WebhookHeaders(id, stamp, header), body, listOf(current), signedAt - 5.minutes).ok()
            verifier.verify(WebhookHeaders(id, stamp, header), body, listOf(current), signedAt + 5.minutes + 1.seconds).error() shouldBe
                WebhookRejection.TimestampOutOfRange
            verifier.verify(WebhookHeaders(id, stamp, header), body, listOf(current), signedAt - 5.minutes - 1.seconds).error() shouldBe
                WebhookRejection.TimestampOutOfRange
        }

        test("正当な再送: 送り手が新しい時刻で署名し直せば、数時間後でも受け付ける(イベント ID は同じ)") {
            val resentAt = signedAt + 3.hours
            val header = StandardWebhooks.signatureHeader(listOf(current), id, resentAt, body)
            verifier
                .verify(
                    WebhookHeaders(id, resentAt.epochSeconds.toString(), header),
                    body,
                    listOf(current),
                    resentAt + 2.seconds,
                ).ok() shouldBe
                VerifiedWebhook(id, resentAt, 0)
        }

        test("ヘッダの欠落・形式の誤り。v1 以外の署名は数えない。鍵がなければ受け口の問題(再送させる)") {
            val header = StandardWebhooks.signatureHeader(listOf(current), id, signedAt, body)
            val stamp = signedAt.epochSeconds.toString()
            verifier.verify(WebhookHeaders(null, stamp, header), body, listOf(current), signedAt).error() shouldBe
                WebhookRejection.MissingHeader("webhook-id")
            verifier.verify(WebhookHeaders(id, " ", header), body, listOf(current), signedAt).error() shouldBe
                WebhookRejection.MissingHeader("webhook-timestamp")
            verifier.verify(WebhookHeaders(id, stamp, null), body, listOf(current), signedAt).error() shouldBe
                WebhookRejection.MissingHeader("webhook-signature")
            verifier.verify(WebhookHeaders("id with space", stamp, header), body, listOf(current), signedAt).error() shouldBe
                WebhookRejection.MalformedHeader("webhook-id")
            verifier.verify(WebhookHeaders(id, "1614265330.5", header), body, listOf(current), signedAt).error() shouldBe
                WebhookRejection.MalformedHeader("webhook-timestamp")
            verifier.verify(WebhookHeaders(id, stamp, header.replace("v1,", "v2,")), body, listOf(current), signedAt).error() shouldBe
                WebhookRejection.MalformedHeader("webhook-signature")
            verifier.verify(WebhookHeaders(id, stamp, "v1,!!!not-base64"), body, listOf(current), signedAt).error() shouldBe
                WebhookRejection.MalformedHeader("webhook-signature")
            verifier.verify(WebhookHeaders(id, stamp, "v1a,xxx $header"), body, listOf(current), signedAt).ok()
            verifier
                .verify(
                    WebhookHeaders(id, stamp, header),
                    body,
                    emptyList(),
                    signedAt,
                ).error()
                .shouldBeInstanceOf<WebhookRejection.NoSecret>()
        }

        test("拒否の理由のコード(メトリクスの属性)") {
            listOf(
                WebhookRejection.MissingHeader("x"),
                WebhookRejection.MalformedHeader("x"),
                WebhookRejection.TimestampOutOfRange,
                WebhookRejection.SignatureMismatch,
                WebhookRejection.NoSecret,
            ).map { it.code } shouldBe
                listOf(
                    "webhook_header_missing",
                    "webhook_header_malformed",
                    "webhook_timestamp_out_of_range",
                    "webhook_signature_invalid",
                    "webhook_secret_unavailable",
                )
        }
    })
