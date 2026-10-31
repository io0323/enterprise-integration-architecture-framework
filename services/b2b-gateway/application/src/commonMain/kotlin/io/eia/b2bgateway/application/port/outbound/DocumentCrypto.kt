package io.eia.b2bgateway.application.port.outbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * B2B の文書の暗号化と署名(PGP。Framework 9.2。ADR-0040 §8)。取引先の鍵は台帳から、自社の鍵は秘密情報から読む。
 * - 受け取った文書: 自社の鍵で復号し、取引先の鍵(差し替えの間は旧い鍵も)で署名を確かめる
 * - 送る応答: 取引先の鍵で暗号化し、自社の鍵で署名する
 */
public interface DocumentCrypto {
    /** 受け取った文書を開く。復号や署名の検証に失敗したら [Opening.Refused](取引先の誤り。Result のエラーにはしない)。 */
    public suspend fun open(
        partnerId: String,
        content: ByteArray,
    ): Result<Opening, DomainError>

    /** 送る応答を、取引先あてに暗号化して署名する。 */
    public suspend fun seal(
        partnerId: String,
        plain: ByteArray,
    ): Result<ByteArray, DomainError>
}

public sealed interface Opening {
    /** 復号と署名の検証が通った。[sha256] は復号した交換の SHA-256、[signer] は署名した鍵のフィンガープリント */
    public class Opened(
        public val content: ByteArray,
        public val sha256: String,
        public val signer: String,
    ) : Opening

    /** 復号できない・署名が取引先の鍵でない・改竄の検知に失敗した。[reason] は理由のコード(値は入れない) */
    public data class Refused(
        val reason: String,
    ) : Opening
}
