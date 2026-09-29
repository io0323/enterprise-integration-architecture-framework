package io.eia.platform.audit

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.getOrNull
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

private fun event(
    details: Map<String, String> = emptyMap(),
    actorId: String = "order-service",
    action: String = "order.create",
    occurredAt: Instant = Instant.parse("2026-09-28T01:02:03.456789123Z"),
): AuditEvent =
    AuditEvent(
        occurredAt = occurredAt,
        actor = Actor(ActorType.SERVICE, actorId),
        action = action,
        target = AuditTarget("order", "ord-1"),
        outcome = AuditOutcome.SUCCESS,
        details = details,
    )

private fun invalid(event: AuditEvent): InvalidAuditEvent = (AuditEventNormalizer.normalize(event) as Result.Err).error

class AuditEventNormalizerSpec :
    FunSpec({
        test("details の値は Masking を通してから記録する") {
            val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJlLXNpZ25hdHVyZQ"
            val normalized =
                AuditEventNormalizer
                    .normalize(event(mapOf("auth" to "Bearer $jwt", "contact" to "taro@example.com", "status" to "created")))
                    .getOrNull()
                    .shouldNotBeNull()
            normalized.details.getValue("auth") shouldNotContain jwt
            normalized.details.getValue("contact") shouldNotContain "taro@example.com"
            normalized.details.getValue("status") shouldBe "created"
        }

        test("時刻はマイクロ秒に切り捨てる") {
            AuditEventNormalizer.normalize(event()).getOrNull()!!.occurredAt shouldBe Instant.parse("2026-09-28T01:02:03.456789Z")
        }

        test("列挙の値はコードで記録する") {
            val normalized = AuditEventNormalizer.normalize(event()).getOrNull()!!
            normalized.actorType shouldBe "service"
            normalized.outcome shouldBe "success"
        }

        test("details の件数・キーの形・伏せた後の長さを検査する") {
            invalid(event((1..33).associate { "k$it" to "v" })).field shouldBe "details"
            invalid(event(mapOf("Bad Key" to "v"))).field shouldBe "details"
            invalid(event(mapOf("body" to "x".repeat(AuditEventNormalizer.MAX_DETAIL_VALUE_LENGTH + 1)))).field shouldBe "details.body"
        }

        test("識別子の欄の制御文字・長さ・空を拒否する") {
            invalid(event(actorId = "a\nb")).field shouldBe "actor.id"
            invalid(event(actorId = "")).field shouldBe "actor.id"
            invalid(event(actorId = "a".repeat(AuditEventNormalizer.MAX_ID_LENGTH + 1))).field shouldBe "actor.id"
            invalid(event(action = "Order Create")).field shouldBe "action"
        }

        test("PostgreSQL で表せない時刻を拒否する") {
            invalid(event(occurredAt = Instant.parse("+10000-01-01T00:00:00Z"))).field shouldBe "occurredAt"
        }

        test("ハッシュ値の形式") {
            ChainHash.GENESIS.hex shouldBe "0".repeat(64)
            ChainHash.parse("A".repeat(64)).shouldBeInstanceOf<Result.Err<*>>()
            ChainHash.sha256("abc".toByteArray()).hex shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        }
    })
