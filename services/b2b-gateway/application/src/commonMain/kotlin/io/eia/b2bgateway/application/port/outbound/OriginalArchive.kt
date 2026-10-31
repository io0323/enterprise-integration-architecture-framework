package io.eia.b2bgateway.application.port.outbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 原本の保管(S3 互換ストレージの Object Lock。COMPLIANCE。ADR-0040 §5・§8)。保持期間は設定で決める。
 * - 受け取った原本と、送った応答の原本は、手を加えずにバイト列のまま(PGP の暗号文)置く
 * - 復号した交換と応答は、自社の保管用の鍵(公開鍵だけをサービスが持つ)で暗号化して、別に置く(後から読めるように。ADR-0040 §8)
 */
public interface OriginalArchive {
    /** 受け取ったバイト列を、手を加えずに置く。 */
    public suspend fun storeReceived(
        partnerId: String,
        content: ByteArray,
    ): Result<StoredOriginal, DomainError>

    /** 復号した交換([sha256] はその SHA-256)を、自社の保管用の鍵で暗号化して置く。置いた場所の参照を返す。 */
    public suspend fun storeReadable(
        partnerId: String,
        interchange: ByteArray,
        sha256: String,
    ): Result<String, DomainError>

    /** 送る応答の原本(取引先あての暗号文 [sealed])と、復号した応答([plain]。保管用の鍵で暗号化する)を置く。 */
    public suspend fun storeSent(
        partnerId: String,
        kind: AcknowledgementKind,
        sealed: ByteArray,
        plain: ByteArray,
    ): Result<StoredAcknowledgement, DomainError>
}

/** 置いた原本。[ref] は置いた場所(キーと版)、[sha256] はバイト列の SHA-256(小文字の 16 進数)。 */
public data class StoredOriginal(
    val ref: String,
    val sha256: String,
)

/** 置いた応答。[original] は送る暗号文、[readableRef] は保管用の鍵で暗号化した応答の場所。 */
public data class StoredAcknowledgement(
    val kind: AcknowledgementKind,
    val original: StoredOriginal,
    val readableRef: String,
)

/** 応答の種類(ADR-0041 §5)。 */
public enum class AcknowledgementKind { CONTRL, APERAK }
