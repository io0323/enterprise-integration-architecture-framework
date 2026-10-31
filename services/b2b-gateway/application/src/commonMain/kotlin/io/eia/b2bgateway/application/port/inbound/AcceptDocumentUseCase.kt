package io.eia.b2bgateway.application.port.inbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 取引先から受け取った文書(SFTP のファイル 1 つ)を受け付ける(ADR-0040 §5・§8、ADR-0041)。
 * 1. 原本(PGP の暗号文)を、解析の前に、手を加えずに保管する(読めない文書も)
 * 2. 復号して署名を確かめる。失敗したら「復号の段階で拒否」として記録と監査だけを行う(交換の中身が読めないので応答を返せない)
 * 3. 復号した交換を、保管用の鍵で暗号化して保管する
 * 4. 3 段階の検査と応答の組み立て([ReceiveInterchangeUseCase])
 * 5. 応答を取引先あてに暗号化・署名し、原本として保管する(送るのは送信の仕組み。P12 ④b)
 * 6. 受け取りの記録・受け付けた注文・送る応答・監査を 1 つのトランザクションで書く
 *
 * 途中で落ちても、同じファイルでやり直せば収まる(原本の保管は版が増えるだけ。記録の前なら、重複の判定は初めての受け取りのまま)。
 *
 * @return 結果。Port のエラーはそのまま返す(呼び出し元がやり直す。ファイルは取引先の SFTP に残す)
 */
public interface AcceptDocumentUseCase {
    public suspend operator fun invoke(document: IncomingDocument): Result<AcceptedDocument, DomainError>
}

/** 受け取ったファイル。[partnerId] はファイルを置いた SFTP の場所の取引先、[fileName] は取引先が付けた名前(記録とログにだけ使う)。 */
public class IncomingDocument(
    public val partnerId: String,
    public val fileName: String,
    public val content: ByteArray,
)

/** 受け付けの結果(運用の通知とメトリクスに使う)。 */
public class AcceptedDocument(
    public val documentId: String,
    public val outcome: DocumentOutcome,
    public val receipt: Receipt?,
)

public enum class DocumentOutcome {
    /** 復号・署名の検証に失敗した(応答を返せない。運用に知らせ、取引先に台帳の連絡先から連絡する) */
    UNREADABLE,

    /** UNB を読めない(応答を返せない) */
    NO_ENVELOPE,

    /** 交換を拒否した(CONTRL の UCI = 4) */
    REJECTED,

    /** 交換の枠を受け付けた(メッセージごとの結果は CONTRL・APERAK と [Receipt.orders]) */
    CHECKED,

    /** 同じ交換の送り直し(取り込まず、前の応答を送り直す) */
    RESENT,
}
