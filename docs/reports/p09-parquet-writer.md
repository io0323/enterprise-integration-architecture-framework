# P09 ②: Parquet の書き出しの手段の比較

ADR-0033 の根拠。ROADMAP P09 の「Parquet の書き出し手段(ライブラリ)を比較して ADR に残す」と、キックオフで挙げたリスク(parquet-java の Hadoop への依存が重い)への答え。

## 結論
- **parquet-java 1.18.1 を、実行時に Hadoop を入れずに使う**(ADR-0033 §1)。distroless の arm64・amd64 の両方で、10 万件・100 万件を書けた。書いたファイルは、DuckDB でも読めて、型・nullable・圧縮(zstd)・内容が契約と一致した。
  - Hadoop なしにするには、`PlainParquetConfiguration` と自前の zstd の圧縮の factory を渡す。既定の圧縮の factory(`CodecFactory`)は、作るだけで Hadoop のクラスを要求する。
  - 受け側の件数の読み取りは、parquet-java の通常の読み手(`ParquetFileReader`)では Hadoop が要る。フッタ(Thrift)を直接読めば Hadoop なしで読めた(ADR-0033 §2)。
- **Hadoop を入れた従来の使い方**: 実行時の jar が 15 個(約 19 MB)から 112 個(約 75 MB)に増える。既知の脆弱性の勧告があるモジュールが 10 個入る。速さとメモリは、Hadoop なしとほぼ同じだった。
- **DuckDB**: 書けるし、zstd も選べる。ただし 2 つの問題がある。
  - 契約の nullable が Parquet に出ない(全列が optional になる)。
  - ヒープの外のメモリで、100 万件ではコンテナのメモリの最大が上限(256 MiB)に達した。
- **Arrow の dataset**: 書けるが、Java の API で圧縮を選べず、圧縮なし(ファイルが約 2 倍)になる。契約の zstd を満たせない。jar は 89 MB。

## 計測の方法
- 計測のコード: `tools/parquet-bench/`(本体のビルドに含めない独立したビルド。README)
- 実行(手動。CI には含めない): `tools/parquet-bench/run.sh <出力のディレクトリ> 3`
- 書く行
  - `contracts/files/sales_daily.v1.yaml` の 8 列を、乱数の種を固定して作った。order_id は UUID の形で、行の順(order_id のバイト順)に作る。
  - 行はメモリに溜めず、1 行ずつ書き手に渡す(④ で DB から読んで書くのと同じ形)。
  - 件数は 10 万件と 100 万件。sales_daily の 1 日の件数の想定は決めていない。100 万件を上の目安にした。
- 1 回の実行で、同じ JVM で 2 回書く。
  - 1 回目(cold)は、クラスの読み込みとネイティブの展開を含む。
  - 2 回目(warm)は、常駐する batch-etl が毎日書くときに近い。
- イメージ
  - images.env の `JAVA_RUNTIME_IMAGE`(`gcr.io/distroless/java21-debian12:nonroot`。JDK 21.0.10+7)に、候補の jar だけを入れた。作りは services/*/app/Dockerfile と同じ。
  - 起動は `java -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError`、uid 65532。
  - arm64 は batch-etl と同じメモリの上限(`--memory 256m`。ヒープの上限は 174 MiB)で動かした。
- 測った値
  - 書き出しの時間。
  - ヒープの最大(メモリのプールの peak の合計)。
  - プロセスの常駐メモリの最大(`/proc/self/status` の `VmHWM`。ヒープの外を含む)。
  - コンテナのメモリの最大(cgroup v2 の `memory.peak`。ページキャッシュを含む)。
- 読み直し
  - 書いたファイルを、ホストで parquet-java(Hadoop 入り)と DuckDB の 2 つの実装で読んだ。
  - フッタから、列の物理型・論理型・repetition・圧縮・行グループの数を読んだ。
  - 次の値が、生成した行と一致するかを確かめた: 件数、金額の合計、ship_region の null の数、ordered_at の最大、order_id を行の順につないだものの MD5。
- 環境
  - マシン: MacBook Pro、Apple M3(8 コア)、メモリ 8 GiB、macOS 26.3.1
  - Docker Desktop 29.8.0(VM のメモリ 7.7 GiB)
  - 計測の間、このリポジトリと関係のない別のプロジェクトのコンテナが 6 個動いていた。値のばらつき(下の表の範囲)はその影響を含む。
- **amd64 はエミュレーション(Apple silicon の Docker Desktop)で動かした。**
  - 1 回目の計測では、amd64 の 100 万件は、4 つの候補のすべての回が 256 MiB の上限で強制終了した。同じ候補の 10 万件では、プロセスの常駐メモリが arm64 より 80〜100 MiB 多かった。エミュレーションのぶんのメモリが上乗せされたためと判断した。
  - そこで amd64 は上限を 768 MiB に広げた(ヒープの上限は 520 MiB)。**amd64 は、動くこと(ネイティブの読み込み)と書いたファイルの確認にだけ使い、時間とメモリは比べない。**
  - 採用した構成の amd64 のネイティブ(エミュレーションでない)の確認は、⑥・⑦ の CI(ubuntu の amd64 のランナー)の E2E で、batch-etl のイメージで行う。

## 候補と版(2026-10-15 に Maven Central で確かめた最新の安定版)
| 候補 | 中身 | 版 |
|---|---|---|
| parquet-java | `parquet-hadoop`。Hadoop なし(`PlainParquetConfiguration`・自前の zstd の factory・フッタは Thrift で直接読む)。snappy-java・aircompressor・commons-pool を外す | parquet-java 1.18.1、zstd-jni 1.5.7-15 |
| parquet-java-hadoop | 同じコードを、`hadoop-common` 入りのクラスパスで動かす(Hadoop の `Configuration` と既定の `CodecFactory`) | 上 + hadoop-common 3.5.0 |
| duckdb | DuckDB JDBC。Appender でメモリの上の表に入れ、`COPY ... TO ... (FORMAT parquet, COMPRESSION zstd)`。`memory_limit` 48 MB、`threads` 2 | duckdb_jdbc 1.5.6.0(イメージのプラットフォームの分類子の jar) |
| arrow | Arrow の dataset(`DatasetFileWriter`。JNI で Arrow C++ の Parquet の書き手)。65,536 行ずつの列のバッチで渡す | arrow-dataset 19.0.0(Arrow C++ 23.0.1) |

## 結果
### 動くこと・書いたファイル
| 候補 | arm64(256 MiB) | amd64(エミュレーション) | 圧縮 | 列の repetition | 行グループ(100 万件) | 読み直し(parquet-java / DuckDB) |
|---|---|---|---|---|---|---|
| parquet-java | 6 / 6 回 | 6 / 6 回 | ZSTD | 契約どおり(ship_region だけ optional) | 1 | 一致 / 一致 |
| parquet-java-hadoop | 6 / 6 回 | 6 / 6 回 | ZSTD | 契約どおり | 1 | 一致 / 一致 |
| duckdb | 6 / 6 回 | 6 / 6 回 | ZSTD | **全列が optional**(NOT NULL が反映されない) | 9 | 一致 / 一致 |
| arrow | 6 / 6 回 | 6 / 6 回 | **UNCOMPRESSED**(選べない) | 契約どおり | 31 | 一致 / 一致 |

- 型は、4 つとも DATE(INT32)・STRING(BINARY)・TIMESTAMP(MICROS, UTC)(INT64)・INT64 だった。DuckDB は INT64 に `INTEGER(64,true)` の注釈を付ける(値の読み方は同じ)。
- 回の数は、アーキテクチャごとに 2 つの件数 × 3 回。1 回の中で cold と warm の 2 つのファイルを書き、どちらも成功した。

### 依存の大きさ
| 候補 | jar の数 | jar の合計 | イメージ(arm64) | ネイティブ |
|---|---|---|---|---|
| parquet-java | 15 | 18.9 MB | 221.0 MB | zstd-jni(jar に linux の amd64・aarch64 などが入っている) |
| parquet-java-hadoop | 112 | 75.0 MB | 277.1 MB | zstd-jni・snappy-java ほか |
| duckdb | 5 | 20.9 MB(amd64 は 23.0 MB) | 223.0 MB | DuckDB(展開して 1 プラットフォーム 50〜60 MB) |
| arrow | 21 | 89.0 MB | 291.0 MB | Arrow C++(jar に linux・macOS・Windows の 5 つ。linux の aarch64 は展開して 71 MB) |

- jar の数と合計には、どの候補にも共通の計測のコード・kotlin-stdlib・slf4j を含む(5 個、約 2 MB)。
- ベースのイメージ(distroless)は arm64 で約 202 MB。
- parquet-java の内訳: zstd-jni 6.3 MB、parquet-column 3.2 MB、parquet-jackson 2.4 MB、parquet-hadoop 1.6 MB、jts-core 1.2 MB、parquet-format-structures 0.8 MB、parquet-encoding 0.8 MB、parquet-common 0.1 MB、javax.annotation-api。

### 時間とメモリ(arm64、256 MiB。3 回の範囲)
| 候補 | 件数 | cold(ms) | warm(ms) | ファイル(MB) | ヒープの最大(MiB) | プロセスの常駐の最大(MiB) | コンテナの最大(MiB) |
|---|---|---|---|---|---|---|---|
| parquet-java | 10 万 | 772〜1,075 | 227〜419 | 2.8 | 28〜29 | 116〜117 | 100〜101 |
| parquet-java | 100 万 | 2,176〜3,734 | 1,575〜3,171 | 27.9 | 75〜88 | 167〜179 | 177〜189 |
| parquet-java-hadoop | 10 万 | 774〜849 | 202〜231 | 2.9 | 37〜38 | 122〜134 | 106〜119 |
| parquet-java-hadoop | 100 万 | 2,008〜2,281 | 1,492〜1,688 | 28.3 | 104〜109 | 191〜197 | 201〜207 |
| duckdb | 10 万 | 750〜2,990 | 192〜214 | 2.8 | 5 | 151〜168 | 169〜185 |
| duckdb | 100 万 | 3,178〜3,685 | 2,551〜3,603 | 28.2 | 5 | 218〜220 | **256(上限)** |
| arrow | 10 万 | 622〜1,010 | 165〜280 | 5.9 | 9 | 172〜193 | 200〜221 |
| arrow | 100 万 | 1,395〜1,650 | 860〜1,004 | 59.4 | 10 | 227〜233 | **256(上限)** |

### 読み方
- **時間はどの候補も、日次のバッチ(SLA は 06:00 JST。ADR-0031)には十分に短い。** 100 万件でも数秒。差は判断の材料にしなかった。Arrow が速いのは圧縮しないため。
- **parquet-java は、行グループ(既定 128 MiB)が埋まるまで、列の値をヒープに溜める。** 100 万件(28 MB)は 1 つの行グループに収まり、ヒープの最大は 75〜88 MiB だった。件数が数倍になると、ヒープの上限(174 MiB)に近づく。そのときは行グループを小さくする(`withRowGroupSize`)か、ファイルを分ける。
- **DuckDB と Arrow は、ヒープは小さいが、ヒープの外のメモリが大きい。**
  - 100 万件で、コンテナのメモリの最大(ページキャッシュを含む)が上限(256 MiB)に達した。プロセスの常駐は 218〜233 MiB で、強制終了はしなかった。
  - JVM の設定(ヒープの 70%)とは別に、ネイティブの上限を決める必要がある。決めないと、ヒープとネイティブが同じ 256 MiB を取り合う。
- **ファイルの大きさ**: zstd の 3 つはほぼ同じ(100 万件で 28 MB)。Arrow は圧縮なしで 59 MB。

### Hadoop なしにするための条件(parquet-java 1.18.1)
各候補の計測の中で、受け側の検査と同じ「件数だけを読む」ことも試した。

| やり方 | Hadoop なし | hadoop-common あり |
|---|---|---|
| `ParquetWriter.Builder.build()`(既定のまま) | **失敗**: `NoClassDefFoundError: org/apache/hadoop/io/compress/zlib/ZlibCompressor$CompressionLevel`(既定の `CodecFactory`) | 書ける |
| 同上 + `withConf(PlainParquetConfiguration())` + `withCodecFactory(自前の zstd)` | 書ける | 書ける |
| `ParquetFileReader.open(InputFile, ParquetReadOptions.builder(PlainParquetConfiguration()).withCodecFactory(自前).build())` | **失敗**: 同じ `NoClassDefFoundError`(`ParquetReadOptions.Builder` のコンストラクタが既定の `CodecFactory` を作る) | **失敗**: `NoClassDefFoundError: org/apache/hadoop/mapreduce/lib/input/FileInputFormat`(mapreduce も要る) |
| フッタを `parquet-format-structures` の `Util.readFileMetaData` で直接読む | 読める(`num_rows` が件数と一致) | 読める |

- 書き手(`ParquetWriter.Builder`・`WriteSupport`)を継承すると、Hadoop の `Configuration` を引数に持つ抽象メソッドの実装が要る。コンパイルにだけ `hadoop-client-api`(推移的な依存なし)を使った。実行時には呼ばれない。
- 外して動いたもの: snappy-java・aircompressor・commons-pool(既定の `CodecFactory` の部品)。
- 外すと失敗したもの:
  - `parquet-jackson`。書き終わりで `ParquetMetadata` のクラスの初期化が `ObjectMapper` を作る。
  - `jts-core`。列の統計の部品(`GeospatialStatistics`)の初期化。

## 脆弱性(OSV の querybatch。2026-10-15)
実行時のクラスパスのモジュール(Gradle の `runtimeClasspath`)を、group・artifact・版で問い合わせた。

| 候補 | モジュールの数 | 勧告のあるモジュール |
|---|---|---|
| parquet-java | 13 | 0 |
| parquet-java-hadoop | 111 | 10: jackson-core・jackson-databind 2.18.6、netty-codec・netty-handler・netty-transport-native-epoll 4.1.130.Final、commons-configuration2 2.10.1、bcprov-jdk18on 1.82、jetty-http・jetty-security・jetty-server 9.4.58 |
| duckdb | 3 | 0(ネイティブに入っている C++ のライブラリは、Maven の DB では見えない) |
| arrow | 20 | 2: jackson-core・jackson-databind 2.21.0(ネイティブの Arrow C++ は見えない) |

- **parquet-java の `parquet-jackson` は、Jackson 2.22.2 を shade して(パッケージ名を変えて)入れている。** shade した jar は、依存の検査(Dependabot・OSV の Maven の問い合わせ)からは見えない。
  - Jackson 2.22.2 には勧告が 4 件ある(GHSA-cxp5-3px4-pw24、GHSA-wv8q-qhhj-9h54、GHSA-7hhh-6rmp-j9qf、GHSA-p6pp-m3f8-5c89。いずれも 2.22.3 で修正)。
  - この経路では `ObjectMapper` を作るだけで、JSON を読まない。受け側はフッタを Thrift で読むので、`ParquetMetadata` を使わない。
  - 扱いは ADR-0033 の Consequences。
- Hadoop を入れた構成の勧告は、Hadoop が使う HTTP サーバ・RPC・設定の部品のもの。Parquet を書く経路では使わないが、版の管理(上書き・確認)の手間が増える。

## 保守の状況(Maven Central の公開日)
| ライブラリ | 直近の版 | 間隔 |
|---|---|---|
| parquet-java | 1.16.0(2025-08)→ 1.17.0(2025-12)→ 1.17.1(2026-05)→ 1.18.0(2026-08)→ 1.18.1(2026-08) | 2〜5 か月。Apache の Parquet の参照実装 |
| duckdb_jdbc | 1.5.3.0(2026-05)→ 1.5.4.0(2026-06)→ 1.5.5.0(2026-07)→ 1.5.5.1(2026-08)→ 1.5.6.0(2026-09) | 毎月 |
| arrow-dataset | 18.2.0(2025-02)→ 18.3.0(2025-05)→ 19.0.0(2026-03) | 18.3.0 から 19.0.0 まで 10 か月 |
| hadoop-common | 3.4.2(2025-08)→ 3.4.3(2026-02)→ 3.5.0(2026-03) | 半年前後 |

## 再現の手順
```bash
export JAVA_HOME=...   # arm64 の JDK(README「開発環境の前提」)
tools/parquet-bench/run.sh /tmp/parquet-bench 3
```
- 出力は、1 回 1 行の `results.jsonl`、`sizes.tsv`、読み直しの `verify.jsonl`。
- 上の表は 3 回目の実行の値。1・2 回目も、範囲はほぼ同じだった。1 回目は amd64 のエミュレーションのメモリが足りずに失敗した。2 回目は parquet-java から使わない圧縮の部品を外す前の構成で、外しても時間とメモリは変わらなかった。
