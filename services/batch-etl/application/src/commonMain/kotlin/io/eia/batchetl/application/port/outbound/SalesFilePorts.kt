package io.eia.batchetl.application.port.outbound

import io.eia.batchetl.domain.BusinessDay
import io.eia.batchetl.domain.SalesFileLine
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 売上ファイルの形式(契約の formats[])。 */
public enum class SalesFileFormat {
    /** Parquet(zstd)。既定 */
    PARQUET,

    /** CSV(RFC 4180)をファイル全体で gzip にしたもの */
    CSV_GZIP,
}

/** 日次のパーティション(mart)から、売上ファイルの行を読む。 */
public interface DailySalesLines {
    /**
     * [day] の行を order_id のバイト順(契約の orderBy)で [consume] に渡す。行はメモリに溜めず、読みながら渡す。
     * [consume] の戻り値をそのまま返す。行は [consume] の中でだけ使える(読み取りはその間だけ開いている)。
     */
    public suspend fun <T> read(
        day: BusinessDay,
        consume: suspend (Sequence<SalesFileLine>) -> Result<T, DomainError>,
    ): Result<T, DomainError>
}

/** 売上ファイルを置く(データファイルの後に、完了の合図のマニフェスト。ADR-0032 §2)。 */
public interface SalesFileStore {
    public suspend fun publish(
        day: BusinessDay,
        revision: Int,
        format: SalesFileFormat,
        lines: Sequence<SalesFileLine>,
    ): Result<ExportedFileInfo, DomainError>
}

/** 置いたファイルの名前と行数。 */
public data class ExportedFileInfo(
    val name: String,
    val records: Long,
)
