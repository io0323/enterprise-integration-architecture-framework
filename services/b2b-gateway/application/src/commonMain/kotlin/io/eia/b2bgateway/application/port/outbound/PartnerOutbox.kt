package io.eia.b2bgateway.application.port.outbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 取引先への送信の仕組み(ADR-0040 §7。P12 の承認の条件 3)。取引先に送るすべての文書(応答の CONTRL・APERAK、P12 ⑦ からは売上ファイル)を、
 * この 1 つの仕組みで送る。送信の経路を分けない。
 * - 送り先は台帳の取引先ごとの SFTP とディレクトリ([Destination])
 * - PGP: 暗号化と署名が済んでいない文書([OutboundDocument.sealed] が false)は、ここで取引先あてに暗号化し、自社の鍵で署名する
 * - Resilience: SFTP の呼び出しは取引先ごとの Resilience(`sftp-{取引先の ID}`。受け取りと共有する)で包む
 * - 重複の防止: 送る前に、取引先の側に同じ正式名のファイルがあるかを確かめ、あれば送らない(ADR-0032 §8 と同じ)
 */
public interface PartnerOutbox {
    public suspend fun deliver(
        partnerId: String,
        document: OutboundDocument,
    ): Result<Delivery, DomainError>
}

/**
 * 送る文書。[remoteName] は取引先の側の正式名(送り直しても同じ名前にする。重複の防止に使う)。
 * [sealed] が true なら [content] は取引先あての暗号文(原本として保管したもの。そのまま送る)。
 */
public class OutboundDocument(
    public val destination: Destination,
    public val remoteName: String,
    public val content: ByteArray,
    public val sealed: Boolean,
)

/** 取引先の SFTP の送り先のディレクトリ(台帳の `sftp.directories`)。 */
public enum class Destination {
    /** 送るファイル(売上ファイルなど。`outbound`) */
    OUTBOUND,

    /** 返す応答(CONTRL・APERAK。`acknowledgements`) */
    ACKNOWLEDGEMENTS,
}

/** 送信の結果。 */
public enum class Delivery {
    SENT,

    /** 取引先の側に同じ正式名のファイルがすでにあったので、送らなかった(前の送信が記録の前に落ちた) */
    ALREADY_PRESENT,
}
