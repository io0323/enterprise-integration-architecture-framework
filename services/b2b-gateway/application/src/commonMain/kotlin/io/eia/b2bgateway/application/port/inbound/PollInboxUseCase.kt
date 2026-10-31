package io.eia.b2bgateway.application.port.inbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 取引先の受け取りのディレクトリを 1 回見回る(ADR-0040 §4)。前の処理で押さえたまま終わっていないファイルを先に処理し、
 * 書き終わったファイルを押さえて、1 つずつ受け付ける([AcceptDocumentUseCase])。受け付けが終わったファイルは処理済みに移す。
 * 1 つのファイルの失敗は、ほかのファイルの処理を止めない(失敗したファイルは押さえたまま残し、次の見回りで続きから処理する)。
 *
 * @return 見回りの結果。一覧・押さえの失敗(取引先の SFTP に接続できないなど)は、そのエラー
 */
public interface PollInboxUseCase {
    public suspend operator fun invoke(partnerId: String): Result<PollSummary, DomainError>
}

/** 見回りの結果(メトリクスと運用の通知に使う)。 */
public class PollSummary(
    public val accepted: List<AcceptedDocument>,
    public val failed: List<FailedFile>,
)

/** 受け付けられなかったファイル(押さえたまま残し、次の見回りで続きから処理する)。 */
public data class FailedFile(
    val name: String,
    val error: DomainError,
)
