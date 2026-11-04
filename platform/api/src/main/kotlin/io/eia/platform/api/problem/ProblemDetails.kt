package io.eia.platform.api.problem

import io.eia.shared.kernel.DomainError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.statuspages.StatusPages
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import kotlin.time.Duration.Companion.seconds

/**
 * エラーの応答を Problem Details(RFC 9457)に揃える(Framework 5。ADR-0022 §2)。
 *
 * | 状況 | 応答 |
 * |---|---|
 * | DB に接続できない([SQLTransientConnectionException]・SQLSTATE のクラス 08) | 503 `service-unavailable`。`Result` に写されなかった経路(冪等の記録など)の備え |
 * | 想定外の例外 | 500 `internal-error`。例外のメッセージとスタックトレースは応答に出さず、Correlation ID の付いた ERROR ログにだけ残す |
 * | 本文を読めない(JSON の解析の失敗など。[BadRequestException]・[ContentTransformationException]) | 400 `bad-request`。パーサのメッセージ(本文の断片を含みうる)は出さない |
 * | [NotFoundException]・どのルートにも当たらない | 404 `not-found` |
 * | メソッドが許可されていない | 405 `about:blank` |
 * | [UnsupportedMediaTypeException] / [PayloadTooLargeException] | 415 / 413 `about:blank` |
 * | キャンセル(クライアントの切断・タイムアウト) | 扱わずに伝える(Ktor の既定でタイムアウトは 504。入口の予算切れは `installRequestDeadline` が 503 で返す) |
 *
 * ルートの処理で [ApplicationCall.respondProblem] / [ApplicationCall.respondError] で返した応答は、上書きしない。
 *
 * **例外は `Plugins` の段階の interceptor で扱い、StatusPages の `exception` は使わない。** StatusPages の例外の処理
 * (`CallFailed`)は `Setup` より前の段階、つまり `ServerObservability`(`Monitoring`)の外側で動く。そこで扱うと、
 * `ServerObservability` には例外がそのまま届き、400 にした応答も 500 として RED メトリクスに数えられ、未処理の例外のログも
 * 二重になる。`Plugins` の段階は `Monitoring` の内側なので、応答の状態コードとログが 1 回で正しく記録され、
 * `correlationId` もその処理のコンテキストから取れる。404 / 405(応答の状態コードだけの場合)は StatusPages で扱う。
 */
private val DB_UNAVAILABLE_RETRY_AFTER = 1.seconds
private const val SQLSTATE_CONNECTION_CLASS = "08"

private fun SQLException.isConnectionFailure(): Boolean =
    this is SQLTransientConnectionException || sqlState?.startsWith(SQLSTATE_CONNECTION_CLASS) == true

public fun Application.installProblemDetails(configure: ProblemDetailsConfig.() -> Unit = {}) {
    attributes.put(ProblemDetailsConfigKey, ProblemDetailsConfig().apply(configure))
    intercept(ApplicationCallPipeline.Plugins) {
        try {
            proceed()
        } catch (cause: CancellationException) {
            // キャンセルは応答しない(タイムアウトの 504 は Ktor が返し、ServerObservability が記録する)
            throw cause
        } catch (
            @Suppress("TooGenericExceptionCaught") cause: Throwable,
        ) {
            // 応答を送り始めた後は、状態コードを変えられないので伝える
            if (call.response.isCommitted) throw cause
            call.respondProblem(problemFor(cause))
        }
    }
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, _ -> call.respondIfNotWritten(Problem(ProblemType.NOT_FOUND)) }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respondIfNotWritten(Problem(ProblemType.aboutBlank(status.value, status.description)))
        }
    }
}

private suspend fun ApplicationCall.respondIfNotWritten(problem: Problem) {
    if (!attributes.contains(ResponseWritten)) respondProblem(problem)
}

@Suppress("MagicNumber") // HTTP の状態コード
private fun problemFor(cause: Throwable): Problem =
    when (cause) {
        is SQLException if cause.isConnectionFailure() -> {
            // DB に届かないのは実装の誤りではなく、一時的な障害。例外のメッセージは接続先を含みうるため、型だけを残す
            logger.warn("DB に接続できないため 503 を返します(error.type={})", cause::class.qualifiedName)
            Problem(ProblemType.SERVICE_UNAVAILABLE, retryAfter = DB_UNAVAILABLE_RETRY_AFTER)
        }

        is BadRequestException, is ContentTransformationException -> {
            // 例外のメッセージは本文の断片を含みうるため、型だけを残す
            logger.debug("リクエストを読めないため 400 を返します exception={}", cause::class.qualifiedName)
            Problem(ProblemType.BAD_REQUEST)
        }

        is NotFoundException -> {
            Problem(ProblemType.NOT_FOUND)
        }

        is UnsupportedMediaTypeException -> {
            Problem(ProblemType.aboutBlank(415, HttpStatusCode.UnsupportedMediaType.description))
        }

        is PayloadTooLargeException -> {
            Problem(ProblemType.aboutBlank(413, HttpStatusCode.PayloadTooLarge.description))
        }

        else -> {
            // メッセージとスタックトレースはログだけに残す(ログはマスキングを通る。ADR-0018 §3)
            logger.error("未処理の例外で 500 を返します(error.type={})", cause::class.qualifiedName, cause)
            Problem(ProblemType.INTERNAL_ERROR)
        }
    }

private val logger = LoggerFactory.getLogger("io.eia.platform.api.problem.ProblemDetails")
