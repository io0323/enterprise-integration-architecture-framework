package io.eia.b2bgateway.application.port.outbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 保管した原本を、置いたときの版のまま読む(送る応答の暗号文。ADR-0040 §7)。 */
public interface OriginalReader {
    /** 参照([StoredOriginal.ref]。`{キー}#{版の ID}`)の原本を読む。 */
    public suspend fun read(ref: String): Result<ByteArray, DomainError>
}
