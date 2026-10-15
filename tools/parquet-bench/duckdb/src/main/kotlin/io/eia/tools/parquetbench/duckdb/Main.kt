package io.eia.tools.parquetbench.duckdb

import io.eia.tools.parquetbench.Bench
import org.duckdb.DuckDBConnection
import java.sql.DriverManager

/**
 * DuckDB(組み込みの DB。JDBC)で書く。メモリの上の表に Appender で行を入れ、`COPY ... TO ... (FORMAT parquet)` で書き出す。
 * DuckDB のメモリはヒープの外なので、上限(memory_limit)を明示する。超えた分は一時ディレクトリに退避する。
 */
fun main(args: Array<String>) {
    Bench.run("duckdb", args) { rows, target ->
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET memory_limit = '$MEMORY_LIMIT'")
                statement.execute("SET threads = $THREADS")
                statement.execute("SET temp_directory = '${target.parent.resolve("duckdb-tmp")}'")
                statement.execute(
                    """
                    CREATE TABLE sales_daily (
                        business_date DATE NOT NULL,
                        order_id VARCHAR NOT NULL,
                        ordered_at TIMESTAMPTZ NOT NULL,
                        status VARCHAR NOT NULL,
                        total_minor_units BIGINT NOT NULL,
                        currency VARCHAR NOT NULL,
                        ship_country VARCHAR NOT NULL,
                        ship_region VARCHAR
                    )
                    """.trimIndent(),
                )
            }
            (connection as DuckDBConnection).createAppender("sales_daily").use { appender ->
                for (row in rows) {
                    appender
                        .beginRow()
                        .appendEpochDays(row.businessDateEpochDay)
                        .append(row.orderId)
                        .appendEpochMicros(row.orderedAtMicros)
                        .append(row.status)
                        .append(row.totalMinorUnits)
                        .append(row.currency)
                        .append(row.shipCountry)
                    val region = row.shipRegion
                    if (region == null) appender.appendNull() else appender.append(region)
                    appender.endRow()
                }
            }
            connection.createStatement().use { statement ->
                // 行の順は契約(orderBy: order_id)。表に入れた順を保つ(preserve_insertion_order は既定で有効)
                statement.execute("COPY sales_daily TO '$target' (FORMAT parquet, COMPRESSION zstd)")
            }
        }
    }
}

/** batch-etl のコンテナ(mem_limit 256m。ヒープは 70%)の残りに収める */
private const val MEMORY_LIMIT = "48MB"
private const val THREADS = 2
