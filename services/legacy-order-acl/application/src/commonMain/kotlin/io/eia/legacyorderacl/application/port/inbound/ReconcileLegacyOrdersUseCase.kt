package io.eia.legacyorderacl.application.port.inbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * レガシーの受注表と、整形済みのトピックの最新の状態を照合する(ADR-0027)。
 *
 * 1. レガシーを読んだ時点の位置(LSN)を基準に、出力をその位置まで処理し終えてから比べる(処理中の変更をずれと誤らない)。
 * 2. 食い違ったキーだけを、時間をおいて同じ手順で比べ直し、それでも食い違うものを「ずれ」とする。
 *
 * @return 照合の結果。待ちの上限の超過や依存先の失敗は Err(ずれではなく、検査の失敗)
 */
public interface ReconcileLegacyOrdersUseCase {
    public suspend operator fun invoke(): Result<ReconciliationReport, DomainError>
}

/** 1 つのキーの照合の結果の種類。 */
public enum class Mismatch {
    /** レガシーにあるが、出力にない(または tombstone) */
    MISSING,

    /** 両方にあるが、状態が違う */
    STALE,

    /** レガシーにないが、出力に値がある */
    EXTRA,
}

/**
 * 照合の結果。
 *
 * @property position 最後の比較でレガシーを読んだ時点の位置(LSN の文字列)
 * @property compared 比べたキーの数(レガシーと出力の和集合)
 * @property drift 比べ直しても食い違ったキー(注文番号 → 種類)。部分の再同期の対象
 * @property unconvertible レガシーの値が変換の規則に合わない(DLQ に入る)キー。既知の差で、ずれに数えない
 * @property digest 全体の SHA-256(キーごとのハッシュをキーの順に並べたものから。報告とログ用)
 */
public data class ReconciliationReport(
    val position: String,
    val compared: Int,
    val drift: Map<String, Mismatch>,
    val unconvertible: Set<String>,
    val digest: String,
) {
    val consistent: Boolean get() = drift.isEmpty()
}
