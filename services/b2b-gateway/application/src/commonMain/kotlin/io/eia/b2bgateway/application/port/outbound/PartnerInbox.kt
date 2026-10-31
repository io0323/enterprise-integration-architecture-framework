package io.eia.b2bgateway.application.port.outbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 取引先の受け取りのディレクトリ(取引先の SFTP。ADR-0040 §4)。書き終わりの判断(台帳の `completion`)は実装が行う。
 * - 押さえ([claim])は、受け取りのディレクトリから処理中のディレクトリへの名前の変更(サーバの中で不可分。2 つの処理が同じファイルを押さえない)
 * - 処理が終わったら([finish])、処理済みのディレクトリに移す(受け取りの記録の ID を名前に付ける。同じ名前の送り直しとぶつからない)
 */
public interface PartnerInbox {
    /** 書き終わった(押さえてよい)ファイルの名前。 */
    public suspend fun ready(partnerId: String): Result<List<String>, DomainError>

    /** 押さえたまま処理が終わっていないファイル(前の処理が途中で落ちた。続きから処理する)。 */
    public suspend fun claimed(partnerId: String): Result<List<String>, DomainError>

    /** 押さえる。ほかの処理が先に押さえていれば false。 */
    public suspend fun claim(
        partnerId: String,
        name: String,
    ): Result<Boolean, DomainError>

    /** 押さえたファイルを読む。 */
    public suspend fun read(
        partnerId: String,
        name: String,
    ): Result<ByteArray, DomainError>

    /** 処理済みにする(受け取りの記録 [documentId] を名前に付けて移す)。 */
    public suspend fun finish(
        partnerId: String,
        name: String,
        documentId: String,
    ): Result<Unit, DomainError>
}
