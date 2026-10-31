package io.eia.b2bgateway.application.usecase

import io.eia.b2bgateway.application.port.inbound.CheckInterchangeUseCase
import io.eia.b2bgateway.application.port.inbound.SyntaxCheck
import io.eia.b2bgateway.application.port.outbound.ControlReferences
import io.eia.b2bgateway.application.port.outbound.EdiSpecs
import io.eia.b2bgateway.domain.ack.Contrl
import io.eia.b2bgateway.domain.ack.ResponseEnvelope
import io.eia.b2bgateway.domain.edifact.EdifactLexer
import io.eia.b2bgateway.domain.edifact.Latin1
import io.eia.b2bgateway.domain.validation.InterchangeCheck
import io.eia.b2bgateway.domain.validation.InterchangeResult
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
import kotlin.time.Clock

/** [CheckInterchangeUseCase] の実装。状態を持たない(応答の管理番号だけを [ControlReferences] から取る)。 */
public class CheckInterchangeService(
    private val specs: EdiSpecs,
    private val references: ControlReferences,
    private val clock: Clock,
) : CheckInterchangeUseCase {
    override suspend fun invoke(document: ByteArray): Result<SyntaxCheck, DomainError> {
        val result = InterchangeCheck.check(EdifactLexer.read(Latin1.decode(document)), specs.orders)
        val header =
            when (result) {
                is InterchangeResult.Unreadable -> return ok(SyntaxCheck(result, null))
                is InterchangeResult.Rejected -> result.header
                is InterchangeResult.Checked -> result.header
            }
        return references.next(header.sender).map { reference ->
            val envelope = ResponseEnvelope(reference, MESSAGE_REFERENCE, clock.now())
            val segments = checkNotNull(Contrl.build(specs.contrl, result, envelope))
            SyntaxCheck(result, Contrl.write(header, segments))
        }
    }

    private companion object {
        // CONTRL の交換にはメッセージが 1 つだけなので、メッセージの参照番号は固定でよい(交換の管理番号で区別する)
        const val MESSAGE_REFERENCE = "1"
    }
}
