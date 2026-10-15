# ADR-0033: Parquet の書き出しと読み取りの手段
- Status: Accepted
- Date: 2026-10-15
- Framework 参照章: 9.1, 9.2, 12.2, 15

## Context
P09 では、batch-etl が日次の売上ファイル(`contracts/files/sales_daily.v1.yaml`。INT-SALES-004)を Parquet で書き、file-exchange が受け取って検査する。ROADMAP P09 は「Parquet の書き出し手段(ライブラリ)を比較して ADR に残す」としている。キックオフで挙げたリスクは、Java の Parquet の参照実装(parquet-java)が Hadoop に依存し、依存が重く脆弱性の範囲も広がることだった。

決めること:
- 書き出しに使うライブラリと、その使い方(Hadoop を実行時に入れるか)。
- 受け側の検査(件数・スキーマ。ADR-0032)で Parquet をどう読むか。
- 実行環境(distroless の Java 21 nonroot。amd64 と arm64。batch-etl はメモリの上限 256 MiB。ADR-0024 §7)で動くこと。

満たすべき契約(sales_daily.v1)は、zstd の圧縮、列の型(DATE・TIMESTAMP(MICROS, UTC)・STRING・INT64)、nullable(ship_region だけが null を許す)、行の順(order_id)。

## Decision
### 1. parquet-java を Hadoop なしで使う
- **parquet-java(`org.apache.parquet:parquet-hadoop`)を、実行時のクラスパスに Hadoop を入れずに使う。** parquet-hadoop の Hadoop への依存は `provided` で、推移的には入らない。
- Hadoop なしで動かすために、次の 3 点を守る(1.18.1 で確かめた。1・2 を守らないと `NoClassDefFoundError` になる)。
  1. 設定は `PlainParquetConfiguration` を渡す(`withConf`)。渡さないと `HadoopParquetConfiguration` が作られる。
  2. 圧縮の factory は自前の zstd だけの実装(`CompressionCodecFactory`。zstd-jni を直接呼ぶ)を渡す(`withCodecFactory`)。既定の `CodecFactory` は Hadoop の zlib のクラスを参照していて、作るだけで失敗する。
  3. 書き手は `ParquetWriter.Builder` と `WriteSupport` を継承して作る(行の型 → 列。Group などの中間の表現を使わない)。どちらにも Hadoop の `Configuration` を引数に持つ抽象メソッドがあるので、**コンパイルにだけ** Hadoop の API(`hadoop-client-api`。推移的な依存なし)を使う(`compileOnly`)。実行時には呼ばれない。
- 圧縮は zstd(水準 3。parquet-java の既定と同じ)。zstd-jni はネイティブ(JNI)で、jar に linux の amd64・aarch64 の分が入っている。起動時に `java.io.tmpdir`(distroless の `/tmp`)に展開する。
- 使わない圧縮の部品は、実行時の依存から外す(`exclude`): snappy-java(ネイティブ)・aircompressor・commons-pool。既定の `CodecFactory` の部品で、自前の factory では読み込まれない(外して動くことを distroless で確かめた)。
- 外せないもの(外すと `NoClassDefFoundError`):
  - `parquet-jackson`(Jackson 2.22.2 を shade した jar)。書き終わりのフッタの組み立てで `ParquetMetadata` のクラスを初期化すると、JSON の変換の部品(`ObjectMapper`)が作られる。JSON を読むことはない。
  - `jts-core`。列の統計の部品(地理の型の統計)の初期化で参照される。

### 2. 受け側の件数・スキーマは、フッタを直接読む
- parquet-java の通常の読み手(`ParquetFileReader`)は Hadoop なしでは開けない。`ParquetReadOptions.Builder` が、設定を渡しても既定の `CodecFactory` を作るため(Hadoop の zlib のクラスを参照)。Hadoop の common を入れても、さらに mapreduce のクラスを要求する。
- 受け側の検査(ADR-0032)に要るのは件数とスキーマで、データの頁の展開は要らない。**フッタ(Thrift の `FileMetaData`)を `parquet-format-structures` の `Util.readFileMetaData` で直接読む。** ファイルの末尾の「フッタの長さ(4 バイト)+ `PAR1`」からフッタの位置を求める。件数は `num_rows`、スキーマは `schema`(列の名前・物理型・論理型・repetition)で、契約と照らす。
- 中身の行まで読む必要が出たら(例 合計値のコントロールレコード。Framework 9.2)、そのときに読み手を決める(この ADR を改訂する)。

### 3. Hadoop を実行時に入れないことを検査で守る
- ③ で `platform/file-transfer` を作るときに、Parquet の書き出しとフッタの読み取りを、Hadoop のない実行時のクラスパスでテストする(`compileOnly` はテストの実行時のクラスパスに入らないので、単体テストがそのまま証拠になる)。
- 本体のビルドの検査(ADR-0032 で AWS SDK と同じ方式)で、`org.apache.hadoop` のモジュールを `runtimeClasspath` に入れない。

### 4. 計測の方法と結果
- 結果と再現の手順は `docs/reports/p09-parquet-writer.md`、計測のコードは `tools/parquet-bench/`(本体のビルドに含めない独立したビルド。手動で動かす)。
- 4 つの候補を、distroless のイメージ(images.env の `JAVA_RUNTIME_IMAGE`)の linux/arm64 と linux/amd64 で動かした。arm64 は batch-etl と同じメモリの上限(256 MiB、ヒープは 70%)。amd64 は Apple silicon の上のエミュレーションで、メモリが上乗せされるので上限を 768 MiB に広げ、動くことの確認にだけ使った(amd64 のネイティブの確認は ⑥・⑦ の CI の E2E)。書いたファイルは、parquet-java と DuckDB の 2 つの実装で読み直し、生成した行と内容(件数・合計・null の数・order_id の順と値)が一致することを確かめた。

## Alternatives Considered
| 候補 | 結果 | 採らない理由 |
|---|---|---|
| **parquet-java(Hadoop なし)** | 採用 | — |
| parquet-java + hadoop-common(従来の使い方) | 書ける。読み手は mapreduce も要る | 実行時の依存が大きく増える(jar の数・大きさ・既知の脆弱性。レポート)。Hadoop の機能(HDFS など)は使わない |
| DuckDB JDBC(`COPY ... TO ... (FORMAT parquet)`) | 書ける。zstd も選べる | ネイティブ(1 プラットフォームで 50〜60 MB)のメモリはヒープの外で、256 MiB の上限の中で別に上限を決める必要がある(100 万件でコンテナの上限に達した)。NOT NULL が Parquet の repetition に反映されず、全列が optional になる(契約の nullable と一致しない)。依存の C++ のライブラリは Maven の脆弱性の DB では見えない |
| Apache Arrow の dataset(JNI) | 書ける | Java の API(`DatasetFileWriter`)に圧縮などの書き出しの設定を渡す引数がなく、圧縮なしになる(契約の zstd を満たせない)。jar に全プラットフォームのネイティブが入って 77 MB。`--add-opens` が要る。Java のリリースの間隔が長い(18.3.0 → 19.0.0 は 10 か月) |
| parquet-java の Hadoop を外した派生(parquet-floor、Carpet など) | 計測しない | parquet-floor は Hadoop のクラスの代わりを自前で持つ派生で、保守を 1 つの小さなプロジェクトに頼る。Carpet は hadoop-common に依存する。parquet-java 本体が Hadoop なしで動く(§1)ので要らない |

## Consequences(トレードオフ)
- 実行時に足す依存は、parquet-java の 6 つの jar と zstd-jni・jts-core・javax.annotation-api の 9 個(約 16.5 MB)で収まる(レポート)。同じ計測で Hadoop を入れると、jar は 112 個(約 75 MB)になった。
- OSV(2026-10-15)で、実行時の依存に既知の脆弱性は 0 件だった。ただし **`parquet-jackson` が shade した Jackson 2.22.2 には勧告が 4 件ある**(2.22.3 で修正)。shade された jar は依存の脆弱性の検査(Dependabot・OSV)からは見えない。この経路では JSON を読まないので、悪用の入口はないと判断した。parquet-java の版を上げるときに、shade した Jackson の版も確かめる(③ で `libs.versions.toml` の parquet の版の行にコメントで書く)。Hadoop を入れた構成では、Jackson・Netty・Jetty・Bouncy Castle などの 10 個のモジュールに勧告があった。
- 自前のコードが 2 つ増える(zstd の圧縮の factory と、フッタの読み取り。それぞれ数十行)。parquet-java の版を上げるときは、Hadoop のないクラスパスのテスト(§3)で、まだ Hadoop なしで動くことを確かめる。上流が Hadoop への参照を増やすと、ここで気づく。
- コンパイルには Hadoop の API の jar(`hadoop-client-api`)を使う。配布物には入らない。
- parquet-java は行グループ(既定 128 MiB)が埋まるまで、列の値をヒープに溜める。100 万件(約 28 MB の Parquet)でヒープの最大は 75〜88 MiB だった(レポート。上限は 174 MiB)。件数が大きく増えたら、行グループの大きさを小さくするか、ファイルを分ける。
- zstd-jni は起動時にネイティブを `/tmp` に展開する。ルートのファイルシステムを読み取り専用にするときは、`/tmp` を tmpfs で書けるようにする。
- 受け側はフッタだけを読み、データの頁を展開しない。データの改竄・欠損は manifest の SHA-256 と件数で検知する(Framework 9.2。ADR-0032)。フッタの件数は、改竄の検知ではなく、manifest の件数との照合と、契約のスキーマとの照合に使う。
