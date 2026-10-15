# parquet-bench

Parquet の書き出しの手段の計測(P09 ②)。結論は [ADR-0033](../../docs/adr/0033-parquet-writer.md)、結果は [docs/reports/p09-parquet-writer.md](../../docs/reports/p09-parquet-writer.md)。

- 本体のビルド(ルートの `settings.gradle.kts`)には含めない、独立した Gradle のビルド。CI では動かさない(計測は手動。Issue #46 の教訓)。
- 候補の版は、このディレクトリの `build.gradle.kts` に直接書く(本体の `libs.versions.toml` には、採用したものだけを ③ で入れる)。

## 構成
| モジュール | 内容 |
|---|---|
| `common` | 計測の行(`contracts/files/sales_daily.v1.yaml` の列。乱数の種を固定)と、計測の本体(`Bench`)。時間・ヒープ・プロセスの RSS(`VmHWM`)・コンテナのメモリ(cgroup v2)を 1 行の JSON で出す |
| `parquet-java` | parquet-java を Hadoop なしで使う(`PlainParquetConfiguration`・自前の zstd の `CompressionCodecFactory`・フッタの直接の読み取り) |
| `parquet-java-hadoop` | 同じコードを hadoop-common 入りのクラスパスで動かす(Hadoop の `Configuration` と既定の `CodecFactory`) |
| `duckdb` | DuckDB JDBC(Appender で入れて `COPY ... TO ... (FORMAT parquet, COMPRESSION zstd)`) |
| `arrow` | Apache Arrow の dataset(`DatasetFileWriter`。JNI) |
| `verify` | 書いたファイルを parquet-java と DuckDB で読み直し、型・圧縮と内容(件数・合計・null の数・order_id の順と値の MD5)を確かめる |

## 動かし方
```bash
# Apple silicon では arm64 の JDK を使う(README「開発環境の前提」)
tools/parquet-bench/run.sh /tmp/parquet-bench 3
```
- 各候補を distroless のイメージ(`infra/local/images.env` の `JAVA_RUNTIME_IMAGE`)に入れ、linux/arm64 と linux/amd64 で、batch-etl と同じメモリの上限(256 MiB。`-XX:MaxRAMPercentage=70`)で、10 万件と 100 万件を書く。
- ホストと違うアーキテクチャはエミュレーションで動く。プロセスのメモリが増えるので、上限を `EMULATED_MEMORY`(既定 768m)に広げる。時間とメモリの値は比べない(動くことと、書いたファイルの確認に使う)。
- 1 回の実行(4 候補 × 2 アーキテクチャ × 2 件数 × 3 回)は Apple M3 でおよそ 7 分。
- 出力: `results.jsonl`(1 回 1 行)、`sizes.tsv`(jar の数と大きさ、イメージの大きさ)、`verify.jsonl`(読み直し)、`files/`(書いたファイル)。
