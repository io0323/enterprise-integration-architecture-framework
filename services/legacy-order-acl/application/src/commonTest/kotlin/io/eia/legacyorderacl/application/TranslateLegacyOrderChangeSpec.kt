package io.eia.legacyorderacl.application

import io.eia.legacyorderacl.application.port.inbound.ChangePosition
import io.eia.legacyorderacl.application.port.inbound.LegacyOrderChange
import io.eia.legacyorderacl.application.port.inbound.TranslationOutcome
import io.eia.legacyorderacl.application.port.outbound.LegacyOrderStatePublisher
import io.eia.legacyorderacl.application.usecase.TranslateLegacyOrderChangeService
import io.eia.legacyorderacl.domain.LegacyOrder
import io.eia.legacyorderacl.domain.LegacyOrderRow
import io.eia.legacyorderacl.domain.LegacyOrderStatus
import io.eia.legacyorderacl.domain.LegacyOrderTranslation
import io.eia.legacyorderacl.domain.TranslationError
import io.eia.legacyorderacl.domain.TranslationFailure
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant

/** 発行を記録する。[failWith] があれば失敗を返す。 */
private class RecordingPublisher(
    var failWith: DomainError? = null,
) : LegacyOrderStatePublisher {
    val published = mutableListOf<Pair<String, LegacyOrder?>>()

    override suspend fun upsert(
        order: LegacyOrder,
        position: ChangePosition,
    ): Result<Unit, DomainError> = failWith?.let { err(it) } ?: ok(Unit).also { published += order.orderNumber to order }

    override suspend fun delete(
        orderNumber: String,
        position: ChangePosition,
    ): Result<Unit, DomainError> = failWith?.let { err(it) } ?: ok(Unit).also { published += orderNumber to null }
}

private val POSITION = ChangePosition(lsn = 26_000_000L, committedAt = Instant.parse("2026-10-08T00:00:00Z"), snapshot = false)

private val ROW =
    LegacyOrderRow(
        orderNumber = "J000000001",
        statusCode = "2",
        customerName = "山田商事株式会社".padEnd(40),
        customerCode = "C0000101",
        amount = "1200.00",
        orderedAtLocalMicros = 1_791_450_000_000_000L,
        updatedAtLocalMicros = LegacyOrderTranslation.UNSET_LOCAL_MICROS,
    )

class TranslateLegacyOrderChangeSpec :
    FunSpec({
        test("登録・更新は、変換した状態を発行する") {
            val publisher = RecordingPublisher()
            val result = TranslateLegacyOrderChangeService(publisher)(LegacyOrderChange.Upsert(ROW, POSITION))
            result shouldBe ok(TranslationOutcome.UPSERTED)
            publisher.published
                .single()
                .second
                ?.status shouldBe LegacyOrderStatus.ALLOCATED
        }

        test("削除は、末尾の空白を除いた注文番号の tombstone を発行する") {
            val publisher = RecordingPublisher()
            TranslateLegacyOrderChangeService(publisher)(LegacyOrderChange.Delete("J00000001 ", POSITION)) shouldBe
                ok(TranslationOutcome.DELETED)
            publisher.published shouldBe listOf("J00000001" to null)
        }

        test("変換できない値は TranslationError を返し、発行しない") {
            val publisher = RecordingPublisher()
            val result = TranslateLegacyOrderChangeService(publisher)(LegacyOrderChange.Upsert(ROW.copy(statusCode = "7"), POSITION))
            result
                .shouldBeInstanceOf<Result.Err<DomainError>>()
                .error
                .shouldBeInstanceOf<TranslationError>()
                .failure shouldBe
                TranslationFailure.UNKNOWN_STATUS_CODE
            TranslateLegacyOrderChangeService(publisher)(LegacyOrderChange.Delete("     ", POSITION))
                .shouldBeInstanceOf<Result.Err<DomainError>>()
            publisher.published.shouldBeEmpty()
        }

        test("発行の失敗(Retryable)はそのまま返す(呼び出し元が読み直す)") {
            val failure = UnavailableError("kafka")
            val service = TranslateLegacyOrderChangeService(RecordingPublisher(failWith = failure))
            service(LegacyOrderChange.Upsert(ROW, POSITION)) shouldBe err(failure)
            service(LegacyOrderChange.Delete("J000000001", POSITION)) shouldBe err(failure)
        }
    })
