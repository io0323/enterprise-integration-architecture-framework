package io.eia.order.adapters.inbound.rest

import io.eia.order.application.port.inbound.DailyOrderSummary
import io.eia.order.application.port.inbound.GetDailyOrderSummaryUseCase
import io.eia.order.domain.BusinessDay
import io.eia.platform.api.problem.Problem
import io.eia.platform.api.problem.ProblemType
import io.eia.platform.api.problem.respondError
import io.eia.platform.api.problem.respondProblem
import io.eia.platform.security.ktor.requireScopes
import io.eia.shared.kernel.Result
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

public const val SCOPE_REPORT: String = "sales.order:report"

/**
 * 日次の集計の API(`GET /v1/order-summaries/daily/{date}`。`sales.order:report`。分析の週次の照合用。ADR-0031 §3)。
 * `authenticate { }` の中に置く(認証は必須)。個々の注文の内容は返さない。日付の形式の誤りは 400。
 */
public fun Route.orderSummaryRoutes(getDailySummary: GetDailyOrderSummaryUseCase) {
    requireScopes(SCOPE_REPORT) {
        get("/v1/order-summaries/daily/{date}") { call.dailySummary(getDailySummary) }
    }
}

/** 契約の `DailyOrderSummary`。 */
@Serializable
internal data class DailyOrderSummaryDto(
    val date: String,
    val timeZone: String,
    val from: String,
    val until: String,
    val orderCount: Long,
    val digest: String,
)

private val summaryJson = Json { encodeDefaults = true }

private suspend fun ApplicationCall.dailySummary(getDailySummary: GetDailyOrderSummaryUseCase) {
    val day =
        (BusinessDay.parse(parameters["date"].orEmpty()) as? Result.Ok)?.value
            ?: return respondProblem(Problem(ProblemType.BAD_REQUEST))
    when (val summary = getDailySummary(day)) {
        is Result.Ok -> {
            respondText(
                summaryJson.encodeToString(DailyOrderSummaryDto.serializer(), summary.value.toDto()),
                ContentType.Application.Json,
            )
        }

        is Result.Err -> {
            respondError(summary.error)
        }
    }
}

private fun DailyOrderSummary.toDto(): DailyOrderSummaryDto =
    DailyOrderSummaryDto(day.toString(), BusinessDay.TIME_ZONE_ID, from.toString(), until.toString(), orderCount, digest)
