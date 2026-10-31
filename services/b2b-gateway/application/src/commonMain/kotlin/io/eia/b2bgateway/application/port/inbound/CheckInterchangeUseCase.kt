package io.eia.b2bgateway.application.port.inbound

import io.eia.b2bgateway.domain.validation.InterchangeResult
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 受け取った交換(原本のバイト列)を、構文とスキーマの段階で検査し、CONTRL を組み立てる(ADR-0041 §4・§5)。
 * 取引先の台帳との照合・交換の管理番号の重複・業務の段階は、このユースケースの後で行う(P12 ②b)。
 *
 * @return 検査の結果と、取引先に返す CONTRL のバイト列(UNB を読めず宛先がわからなければ null)。
 *   管理番号を振れなければ [ControlReferences][io.eia.b2bgateway.application.port.outbound.ControlReferences] のエラー
 */
public interface CheckInterchangeUseCase {
    public suspend operator fun invoke(document: ByteArray): Result<SyntaxCheck, DomainError>
}

/** 構文とスキーマの段階の結果。[contrl] は受け取った交換の文字セットで書いた CONTRL。 */
public class SyntaxCheck(
    public val result: InterchangeResult,
    public val contrl: ByteArray?,
)
