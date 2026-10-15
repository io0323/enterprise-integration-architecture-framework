package io.eia.platform.reliability

// 違反: Parquet と Hadoop は platform/file-transfer だけ(ADR-0033 §3)
import org.apache.parquet.hadoop.ParquetWriter

class UsesParquet(
    val writer: ParquetWriter<Any>,
    val conf: org.apache.hadoop.conf.Configuration,
)
