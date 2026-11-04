package io.eia.b2bgateway.app

import com.zaxxer.hikari.HikariDataSource
import io.eia.b2bgateway.adapters.order.SubmissionSnapshot
import io.eia.b2bgateway.adapters.persistence.JdbcOrderSubmissions
import io.eia.b2bgateway.application.port.inbound.DeliverFilesUseCase
import io.eia.b2bgateway.application.port.inbound.PollInboxUseCase
import io.eia.b2bgateway.application.port.inbound.SendAcknowledgementsUseCase
import io.eia.b2bgateway.application.port.inbound.SubmitOrdersUseCase
import io.eia.platform.audit.anchor.AnchorCycle
import io.eia.platform.audit.anchor.AnchorOutcome
import io.eia.shared.kernel.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.Koin
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * b2b-gateway の常駐のプロセス([B2bGatewayServer])が繰り返す仕事。1 回分を実行し、結果をログとメトリクスに出す
 * (1 つの取引先・1 回の失敗は、ほかを止めない。次の回にやり直す)。
 */
internal object B2bGatewayJobs {
    private val logger = LoggerFactory.getLogger(B2bGatewayJobs::class.java)

    /** 取引先ごとに受け取りのディレクトリを 1 回見回る。 */
    @Suppress("TooGenericExceptionCaught") // どの例外でも、ほかの取引先の見回りを止めない
    internal suspend fun pollOnce(koin: Koin) {
        val poll = koin.get<PollInboxUseCase>()
        val metrics = koin.get<B2bGatewayMetrics>()
        koin.get<EdiPartners>().inbound.forEach { partnerId ->
            val summary =
                try {
                    poll(partnerId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    metrics.pollCrashed(partnerId)
                    logger.error("取引先 {} の見回りが想定していない例外で失敗しました(error.type={})", partnerId, e::class.qualifiedName)
                    return@forEach
                }
            when (summary) {
                is Result.Ok -> {
                    metrics.polled(partnerId, summary.value)
                    summary.value.failed.forEach { logger.warn("取引先 {} のファイル {} を受け付けられません({})", partnerId, it.name, it.error.code) }
                }

                is Result.Err -> {
                    metrics.pollFailed(partnerId)
                    logger.warn("取引先 {} の受け取りのディレクトリを見られません({})", partnerId, summary.error.code)
                }
            }
        }
    }

    /** 取引先ごとに送信待ちの応答を 1 回送る。 */
    internal suspend fun sendOnce(koin: Koin) {
        val send = koin.get<SendAcknowledgementsUseCase>()
        val metrics = koin.get<B2bGatewayMetrics>()
        koin.get<EdiPartners>().acknowledgements.forEach { partnerId ->
            when (val summary = send(partnerId)) {
                is Result.Ok -> {
                    metrics.sent(partnerId, summary.value)
                    summary.value.failed.forEach { logger.warn("取引先 {} に応答 {} を送れません({})", partnerId, it.id, it.error.code) }
                }

                is Result.Err -> {
                    logger.warn("取引先 {} の送信待ちの応答を読めません({})", partnerId, summary.error.code)
                }
            }
        }
    }

    /** 受け付けた注文を 1 回送る。 */
    internal suspend fun submitOnce(koin: Koin) {
        when (val summary = koin.get<SubmitOrdersUseCase>()()) {
            is Result.Ok -> {
                koin.get<B2bGatewayMetrics>().submitted(summary.value)
                summary.value.failed.forEach { logger.warn("注文 {} を社内に取り込めません({})", it.id, it.error.code) }
                summary.value.expired.forEach { logger.error("注文 {} は送り直しの上限を過ぎました(手の対応が要ります)", it) }
            }

            is Result.Err -> {
                logger.warn("送信待ちの注文を読めません({})", summary.error.code)
            }
        }
    }

    /** 受け渡しの場所を一覧し、取引先にファイルを送る(ADR-0040 §7)。 */
    internal suspend fun deliverFilesOnce(koin: Koin) {
        when (val summary = koin.get<DeliverFilesUseCase>()()) {
            is Result.Ok -> {
                koin.get<B2bGatewayMetrics>().filesDelivered(summary.value)
                summary.value.failed.forEach {
                    logger.warn("ファイル {} を取引先 {} に送れません({})", it.name, it.partnerId ?: "-", it.error.code)
                }
                summary.value.invalid.forEach { logger.warn("受け渡しの場所のマニフェスト {} の形が違います", it) }
                summary.value.deleted.forEach { logger.info("保存期間を過ぎたファイル {} を受け渡しの場所から消しました", it) }
            }

            is Result.Err -> {
                koin.get<B2bGatewayMetrics>().handoffUnavailable()
                logger.warn("受け渡しの場所を一覧できません({})", summary.error.code)
            }
        }
    }

    /** 送信待ちの期限のメトリクスの元を DB から読み直す。 */
    internal suspend fun refreshSnapshot(koin: Koin) {
        when (val snapshot = koin.get<JdbcOrderSubmissions>().snapshot()) {
            is Result.Ok -> koin.get<AtomicReference<SubmissionSnapshot>>().set(snapshot.value)
            is Result.Err -> logger.warn("送信待ちの期限を読めません({})", snapshot.error.code)
        }
    }

    /**
     * 監査のアンカーの検査と保存(ADR-0017 §5)。改竄の疑いがあれば保存せず ERROR を残す(対応は docs/runbooks/audit-verify.md)。
     * ストレージや DB に届かなければ次の回でやり直す。成否はメトリクス(AnchorMetrics)で監視する。
     */
    @Suppress("TooGenericExceptionCaught") // DB の接続の失敗はどの種類でも次の回にやり直す
    internal suspend fun anchorOnce(
        cycle: AnchorCycle,
        dataSource: HikariDataSource,
    ) {
        val result =
            try {
                withContext(Dispatchers.IO) { dataSource.connection.use { cycle.run(it) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("監査のアンカーの検査を終えられませんでした(error.type={})", e::class.qualifiedName)
                return
            }
        when (result) {
            is Result.Err -> {
                logger.warn("監査のアンカーの検査を終えられませんでした。次の回でやり直します(error.code={})", result.error.code)
            }

            is Result.Ok -> {
                when (val outcome = result.value) {
                    is AnchorOutcome.Published -> {
                        logger.info("監査のアンカーを保存しました(key={}, seq={})", outcome.anchor.key, outcome.anchor.anchor.seq)
                    }

                    is AnchorOutcome.Rejected -> {
                        outcome.findings.forEach { logger.error("監査記録に改竄の疑いがあります({}): {}", it.code, it.describe()) }
                        logger.error("改竄の疑いがあるため、監査のアンカーを保存しません(対応: docs/runbooks/audit-verify.md)")
                    }

                    else -> {
                        logger.info("監査のアンカーの検査を終えました({})", outcome::class.simpleName)
                    }
                }
            }
        }
    }
}
