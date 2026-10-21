package io.eia.webhookreceiver.adapters.inbound.rest

import io.eia.platform.api.problem.Problem
import io.eia.platform.api.problem.ProblemType
import io.eia.platform.api.problem.respondError
import io.eia.platform.api.problem.respondProblem
import io.eia.platform.webhook.StandardWebhooks
import io.eia.platform.webhook.VerifiedWebhook
import io.eia.platform.webhook.WebhookHeaders
import io.eia.platform.webhook.WebhookRejection
import io.eia.platform.webhook.WebhookVerifier
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.webhookreceiver.application.port.inbound.ReceiveWebhook
import io.eia.webhookreceiver.application.port.inbound.ReceiveWebhookUseCase
import io.eia.webhookreceiver.domain.ReceiptOutcome
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Webhook の受け口(ADR-0035。契約 contracts/openapi/crm-webhook.v1.yaml)。
 *
 * 1. 本文を **解析せずに** 生のバイト列で読む(上限 [MAX_BODY_BYTES]。超えたら 413)。
 * 2. 署名・時刻・ヘッダを検証する([WebhookVerifier]。前後 5 分・定数時間の比較・差し替えの間は 2 つの鍵)。失敗は記録せずに拒否する
 *    (ヘッダの欠落・形式の誤りは 400、署名の不一致・時刻の範囲外は 401、鍵を読めなければ 503)。
 * 3. 受け取りの記録と取り込みのトピックへの Outbox を同じトランザクションで書き([ReceiveWebhookUseCase])、すぐに 204 を返す。
 *    同じ ID の再送にも 204 を返す(二度は取り込まない)。本文の形(JSON か・項目)は見ない(取り込みの側で隔離する。ADR-0035 §3)。
 *
 * @param source 送り手(取り込みのイベントの `source`。Flow の trigger が選ぶのに使う)
 */
public fun Route.webhookReceiver(
    path: String,
    source: String,
    receiver: WebhookReceiver,
) {
    val metrics = receiver.metrics
    post(path) {
        val body = call.readBody()
        if (body == null) {
            metrics.count(source, REJECTED, BODY_TOO_LARGE)
            call.respondProblem(
                Problem(ProblemType.aboutBlank(HttpStatusCode.PayloadTooLarge.value, HttpStatusCode.PayloadTooLarge.description)),
            )
            return@post
        }
        val headers =
            WebhookHeaders(
                call.request.header(StandardWebhooks.ID),
                call.request.header(StandardWebhooks.TIMESTAMP),
                call.request.header(StandardWebhooks.SIGNATURE),
            )
        when (val verified = receiver.verifier.verify(headers, body, receiver.secrets.read(), receiver.clock.now())) {
            is Result.Err -> {
                call.reject(verified.error, source, metrics)
            }

            is Result.Ok -> {
                val command =
                    ReceiveWebhook(
                        webhookId = verified.value.id,
                        source = source,
                        signedAt = verified.value.signedAt,
                        contentType = call.request.header(HttpHeaders.ContentType) ?: DEFAULT_CONTENT_TYPE,
                        bodySha256 = sha256(body),
                        body = body,
                    )
                call.accept(receiver.receive(command), verified.value, source, metrics)
            }
        }
    }
}

/** 受け口の依存。鍵は [secrets] から要求のたびに読む。 */
public data class WebhookReceiver(
    val receive: ReceiveWebhookUseCase,
    val secrets: WebhookSecrets,
    val metrics: WebhookMetrics,
    val clock: Clock = Clock.System,
    val verifier: WebhookVerifier = WebhookVerifier(),
)

/** 受け付ける本文の上限(Gateway の上限と同じ 256 KiB。ADR-0035 §5)。 */
public const val MAX_BODY_BYTES: Int = 256 * 1024

private const val REJECTED = "rejected"
private const val FAILED = "failed"
private const val BODY_TOO_LARGE = "webhook_body_too_large"
private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
private val RETRY_AFTER = 5.seconds

/** 本文を生のバイト列で読む。上限を超えたら null(読み切らずに止める)。 */
private suspend fun ApplicationCall.readBody(): ByteArray? {
    val declared = request.contentLength()
    if (declared != null && declared > MAX_BODY_BYTES) return null
    val bytes = receiveChannel().readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
    return bytes.takeIf { it.size <= MAX_BODY_BYTES }
}

private suspend fun ApplicationCall.reject(
    rejection: WebhookRejection,
    source: String,
    metrics: WebhookMetrics,
) {
    val problem =
        when (rejection) {
            is WebhookRejection.MissingHeader, is WebhookRejection.MalformedHeader -> Problem(ProblemType.BAD_REQUEST)
            WebhookRejection.TimestampOutOfRange -> Problem(ProblemType.WEBHOOK_TIMESTAMP_OUT_OF_RANGE)
            WebhookRejection.SignatureMismatch -> Problem(ProblemType.WEBHOOK_SIGNATURE_INVALID)
            WebhookRejection.NoSecret -> Problem(ProblemType.SERVICE_UNAVAILABLE, retryAfter = RETRY_AFTER)
        }
    metrics.count(source, if (rejection == WebhookRejection.NoSecret) FAILED else REJECTED, rejection.code)
    respondProblem(problem)
}

private suspend fun ApplicationCall.accept(
    result: Result<ReceiptOutcome, DomainError>,
    verified: VerifiedWebhook,
    source: String,
    metrics: WebhookMetrics,
) {
    when (result) {
        is Result.Err -> {
            metrics.count(source, FAILED, result.error.code)
            // Retryable(DB に届かないなど)は 503 と Retry-After。記録していないので、送り手の再送は重複にならない
            respondError(result.error)
        }

        is Result.Ok -> {
            val outcome =
                when (val value = result.value) {
                    ReceiptOutcome.Recorded -> "recorded"
                    is ReceiptOutcome.Duplicate -> if (value.sameBody) "duplicate" else "duplicate_body_mismatch"
                }
            metrics.count(source, outcome, key = if (verified.matchedSecret == 0) "current" else "previous")
            respond(HttpStatusCode.NoContent)
        }
    }
}

private fun sha256(body: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))
