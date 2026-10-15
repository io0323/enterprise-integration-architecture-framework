# ADR-0032: ファイル連携の部品(platform/file-transfer)
- Status: Accepted
- Date: 2026-10-15
- Framework 参照章: 9.1, 9.2, 9.3, 12.2, 13, 16

## Context
P09 では、batch-etl が日次の売上ファイル(`contracts/files/sales_daily.v1.yaml`。INT-SALES-004)を S3 互換ストレージ(SeaweedFS。ADR-0015)に置き、file-exchange が受け取って検査し、取引先に SFTP で送る。Framework 9.2 は次を求める。
- マニフェスト(件数・SHA-256・スキーマの版・traceparent)をデータファイルと対で送り、受け側で必ず検証する。
- 書き込み中のファイルを読ませない(一時名 → 名前の変更、またはマニフェストの到着を完了の合図にする)。
- 欠損・改竄・二重の取り込みを構造的に防ぐ(9.3)。

S3 には名前の変更(rename)もアトミックな移動もない。受け側の archive / quarantine への移動はコピーと削除の 2 つの操作になり、間で落ちうる。また、P08 で platform/batch に置いた S3 の接続(`S3ObjectSource`)は、ADR-0030 で「file-transfer ができたら寄せる」としていた。Issue #12 のコメントでは、AWS SDK を使ってよい範囲を限ることを求めている(#6 のコメントで、用途ごとの資格情報も)。

この ADR は P09 ③a(S3 の部分)で決めることを書く。SFTP と PGP は ③b でこの ADR に追記する(§8)。Parquet の書き出しの手段は ADR-0033。

## Decision
### 1. モジュールと置き場
- `platform/file-transfer` を作る。置くもの: S3 互換ストレージの置き場(`ObjectReader` / `ObjectStore`。実装は `S3ObjectStore`)、マニフェスト(`FileManifest`)、データファイルの形(`FileLayout`・`FileName`)、書き出し(`FilePublisher`)、検査(`FileVerifier`)、移動(`FileMover`)。③b で PGP と SFTP を足す。
- platform/batch の `S3ObjectSource` と `FileManifest` はここに移した(ADR-0030 の改訂履歴)。platform/batch は file-transfer の `ObjectReader`(読むだけ)でファイルの到着を監視する(batch → file-transfer)。platform/batch は AWS SDK に依存しない。
- 資格情報は呼び出しのたびに `SecretProvider` から取る(ADR-0019 §6。file-transfer → security)。**用途ごとに権限を絞った identity を使う**(#6 のコメント)。バケット `eiaf-file-exchange` で次のように分ける(compose は ⑥)。

  | identity | 使う人 | 権限 |
  |---|---|---|
  | `eiaf-file-batch-etl` | batch-etl(送り手) | 受け口(`inbox/` の下の自分の接頭辞)への書き込みだけ。読み取り・一覧・削除はできない |
  | `eiaf-file-exchange` | file-exchange(受け手) | 読み取り・一覧・書き込み(archive / quarantine への移動と受け口の削除) |
  | 監視だけの identity(必要なら) | 起動の判定だけをするプロセス | 読み取り・一覧 |

  書き手が受け口の外に書けないこと、読み取りだけの identity が書けないことは、統合テスト(`FileTransferIT`)で確かめる。

### 2. 書き出し: データファイルを置いてから、最後にマニフェストを置く
- `FilePublisher.publish` の手順:
  1. データファイルをローカルの一時ファイル(`java.io.tmpdir`。distroless では `/tmp`)に書き、件数・SHA-256・大きさを求める。Parquet は列ごとにまとめて書くので、置き場に直接は流さない。
  2. データファイルを **正式名で、1 回の PUT で** 置く。S3 の PUT は置き終わるまでほかから見えない。名前の変更がないので一時名は使わない(一時名にすると、コピーと削除が増え、その間で落ちたときの扱いも増える)。
  3. **最後にマニフェストを置く。マニフェストの到着が完了の合図**(Framework 9.2 の「マニフェスト到着を完了合図」。ADR-0030 §4 のファイルの到着の起動はマニフェストで動く)。
- 途中で落ちても、同じ名前でもう一度呼べばよい(どちらも上書き)。マニフェストのないデータファイルは、受け側からは見えない。
- 行の値は契約の型(`ColumnType`)で、列の順に渡す。型・null・列の数・取りうる値(enum)が合わなければ、何も置かずに使い方の誤り(`FileTransferMisuse`)。誤りの説明には列の名前と型だけを入れ、値は入れない。traceparent・Correlation ID の形式は、データファイルを書く前に受け側と同じ検査(`FileManifest.parse`)で確かめる。
- 形式
  - Parquet: zstd。Hadoop を実行時に使わない(ADR-0033)。timestamp は TIMESTAMP(MICROS, UTC)、date は DATE。nullable でない列は REQUIRED。
  - CSV + gzip: RFC 4180(契約の delimiter・quote・header・lineSeparator・nullValue)。区切り・囲み・改行を含む値と、**空の文字列(null と区別するため)** を囲む。timestamp は ISO 8601 の Z 付き(小数 6 桁)。
- 名前は `{system}_{dataset}_{yyyyMMddHHmmss}_{seq}.{ext}`(Framework 9.2)。時刻は UTC で書く。マニフェストは `{base}.manifest.json`。
- 契約(`contracts/files/*.yaml`)は実行時には読まない。利用者がコード(`FileLayout`)にし、一致させる(④ で、契約と `FileLayout` が一致することを単体テストで確かめる)。

### 3. 検査: 判定だけを返し、ファイルを動かさない
- `FileVerifier.verify(prefix, manifestKey)` は次の順に調べ、最初に見つかった不合格の理由(`RejectionReason`)を返す。
  1. マニフェストの形式(`MANIFEST_INVALID`)
  2. マニフェストの `file` がマニフェストの名前と対応する(`NAME_MISMATCH`)
  3. スキーマの版が受け付けているもの(`SCHEMA_VERSION_UNKNOWN`)
  4. 拡張子が契約の形式(`FORMAT_UNSUPPORTED`)
  5. データファイルがある(`DATA_MISSING`)
  6. 大きさ(`SIZE_MISMATCH`。マニフェストに `sizeBytes` があるとき)
  7. SHA-256(`CHECKSUM_MISMATCH`)
  8. 中身を形式どおりに読める(`CONTENT_UNREADABLE`)、列が契約と同じ(`SCHEMA_MISMATCH`)、件数(`RECORD_COUNT_MISMATCH`)。Parquet はフッタの列(名前・順序・物理型・論理型・nullable)と `num_rows`(ADR-0033 §2)。CSV は gzip を解き、ヘッダの列と、囲みの外の改行で区切ったレコードの数。囲みが閉じていない・最後のレコードが改行で終わらないファイルは読めない扱い。
- 合格(`Verification.Accepted`)・不合格(`Verification.Rejected`)のどちらも **判定だけ** で、ファイルは動かさない。置き場を読めない・一時ファイルを書けないときは失敗(`FileTransferError`)で、不合格とは区別する(リトライの対象)。
- 不合格の説明は固定の文だけで、ファイルの中身の値は入れない(個人情報を含みうる。Framework 14.3)。
- データファイルの頁は展開しない。改竄と欠損は SHA-256 と件数で検知する。マニフェスト自体の改竄は、SFTP の経路では PGP の署名で検知する(③b)。

### 4. 移動: コピーしてから消す。どこで落ちても、同じ移動を呼び直せば収まる
- `FileMover.archive` / `quarantine` は、受け口の `{prefix}{base}.` で始まるオブジェクト(データファイルとマニフェスト)を、次の順に移す。
  1. マニフェスト以外(データファイル)を、1 つずつコピーしてから消す。
  2. quarantine なら、理由のファイル `{base}.reason.json`(理由のコード・固定の説明・時刻・Correlation ID)を置く。
  3. **最後にマニフェストを** コピーしてから消す。
  4. 移動先にマニフェスト(archive ならデータファイルも)があることを確かめる。なければ `MoveIncomplete`(運用に引き継ぐ)。
- コピー元がないコピーは何もしない(前の呼び出しで移した)。コピーと削除は上書き・ないキーの削除でも成功するので、**同じ引数で何度呼んでも、同じ結果で終わる**。マニフェストを最後に移すので、受け口にマニフェストが残る間は、ファイルの到着の起動がもう一度起きる。
- **危険な落ち方**: データファイルを移した後・マニフェストを移す前に落ちると、受け口にはマニフェストだけが残る。このとき検査からやり直すと、データファイルの欠損(`DATA_MISSING`)として quarantine に送ってしまう(合格していたファイルを失う)。そのため、**検査と移動を分け**、呼び出し側は移動を始める前に「どちらへ移すか」を記録し、やり直しでは検査をせずに記録した移動を呼ぶ(§5)。
- 単体テスト(`FileMoverSpec`)と統合テスト(`FileTransferIT`)で、移動の **すべての書き込みの直前で落とし**、やり直すと archive(不合格のファイルは quarantine)に収まることを確かめる。上の危険な落ち方では、検査からやり直すと `DATA_MISSING` になり、記録した移動を呼び直すと archive に収まって quarantine に送られないことを、明示的に確かめる。
- 同じファイルを 2 つのインスタンスが同時に移すことはない。ファイルの到着の起動はファイルの名前をパーティションにし、platform/batch の advisory lock で 1 つだけ動く(ADR-0030 §2)。

### 5. 受け取りの記録と重複: file-exchange の DB に置く
- 受け取りの記録(重複の判定と、移動の決定)は **file-exchange の DB の表 `received_file`** に置く(⑤ で作る)。
  - 主キーはファイル名(`base`)。SHA-256・移動先(archive / quarantine)・不合格の理由・状態(移動中 / 済み)・時刻を持つ。
  - 手順: 記録がなければ検査し、**移動の決定を「移動中」として記録してから移す**。移し終えたら「済み」にする。記録が「移動中」なら、検査をせずに記録した移動を続ける(§4 の危険な落ち方からの再開)。
  - 同じ名前で同じ SHA-256 のファイルがもう一度届いたら「重複」として、取り込みも取引先への送信もしない。同じ名前で SHA-256 が違うものは不合格として quarantine に移す(送り直すときは連番を変えてもらう)。重複の受け口のファイルの扱い(理由を付けて quarantine に移すなど)は ⑤ で決める。
- **S3 の archive/ の中身を記録の代わりにしない理由**
  - archive/ は保存期間(ライフサイクル。#22)で消える。消えた後に同じファイルが届くと、重複に気づけない。
  - 一覧と存在の確認だけでは、「移動中」と「済み」を区別できない(§4 の危険な落ち方で、archive/ にデータファイルだけがある状態と、quarantine に送るべき状態を見分けられない)。
  - DB の一意の制約で、同じファイルの記録が 2 つできないことを保証できる。
- ③a の部品は、検査の結果(`Verification`)と移動(`FileMover`)を分けた API にして、この判断を呼び出し側が持てるようにした。統合テストでは、`received_file` の代わりにメモリの上の記録で同じ手順を組み、§4 の落ち方を確かめた。

### 6. AWS SDK・Parquet・Hadoop を使ってよい範囲
- AWS SDK(`software.amazon.awssdk`)を本番コードで使ってよいのは、`platform/file-transfer` と `platform/audit` のアンカー(Object Lock。ADR-0017)だけ。ほかは file-transfer の置き場を使う(#12 のコメント)。テストのソースセット(バケットの作成など)は除く。
- Parquet(`org.apache.parquet`)と Hadoop(`org.apache.hadoop`)は `platform/file-transfer` だけ(計測の `tools/parquet-bench` を除く。ADR-0033 §3)。
- 検査は 3 つ。
  - Konsist の import・完全修飾名の検査(`awsSdkOnlyInAllowedModules`・`parquetOnlyInFileTransfer`)。
  - 依存の宣言の検査(`LibraryDeclarationRules`): `libs.aws.*` を本番の設定(`api`・`implementation`・`compileOnly`・`runtimeOnly`)で宣言してよいのは file-transfer と audit だけ。`libs.parquet.*`・`libs.hadoop.*` は file-transfer だけ。Hadoop は `compileOnly` だけ。テストの設定は除く。
  - 実行時のクラスパスに Hadoop がないこと(`platform/file-transfer` の `verifyNoHadoopAtRuntime`。`check` に含める)。

### 7. 監視と運用(⑤・⑥)
- 不合格の数(理由ごと)・移動の失敗・受け口に残っている時間をメトリクスにし、アラートと Runbook を ⑤・⑥ で足す。ラベルにファイル名は入れない(系列が増え続けるため)。

### 8. SFTP と PGP(③b で追記する)
承認済みの方針(詳細・統合テスト・使う暗号の方式の一覧は ③b でこの節に書く)。
- SFTP のクライアントは sshj。ホスト鍵は known_hosts の形で事前に登録し、一致しなければ止める。検証を外す設定は作らない。SHA-1 の `ssh-rsa` などの古い方式は使わない設定にし、使う方式の一覧をここに書く。古い方式しか持たないホスト・別の鍵のホストへの接続を拒否することを統合テストで確かめる。
- 取引先への経路は PGP で暗号化と署名の両方を行う(取引先の公開鍵で暗号化、自社の秘密鍵で署名。Framework 12.2)。受け側は署名を検証してから復号する。鍵は `SecretProvider` から読む。

## Alternatives Considered
| 案 | 採らない理由 |
|---|---|
| S3 でも一時名で書いて、コピーと削除で正式名にする | 名前の変更がないので、コピーと削除が増え、その間で落ちたときの扱いも増える。S3 の PUT は置き終わるまで見えないので、正式名で 1 回置いてマニフェストを最後に置けば、Framework 9.2 の完了の合図を満たす |
| マニフェストを先に、データファイルを後に移す | 受け口にデータファイルだけが残ったとき、起動の合図(マニフェスト)がないので、移動の続きが起動されない |
| 受け取りの記録を S3 の archive/ の中身(一覧)で代える | §5。保存期間で消える・移動中と済みを区別できない・一意の制約がない |
| 検査の中で移動まで行う(1 つの API) | §4 の危険な落ち方で、やり直しが検査から始まり、合格したファイルを quarantine に送る。決定の記録を呼び出し側(DB)が持てない |
| 受け側で Parquet の全部の行を読んで検査する | 改竄と欠損は SHA-256 と件数で足りる。全部を読むと、読み手に Hadoop が要る(ADR-0033 §2)。合計値のコントロールレコード(Framework 9.2)が要るときに改訂する |

## Consequences(トレードオフ)
- 受け取りの手順(決定の記録 → 移動 → 済み)は file-exchange の責任になる。③a の部品だけを使って、記録なしで検査と移動を続けて呼ぶと、§4 の危険な落ち方で合格したファイルを失う。部品のドキュメント(`FileMover`)にこの前提を書いた。
- データファイルはローカルの一時ファイルを経由する(書き出し・検査とも)。1 ファイルの大きさだけ `/tmp` の空きが要る(100 万件の Parquet で約 28 MB。ADR-0033 のレポート)。ルートのファイルシステムを読み取り専用にするときは `/tmp` を tmpfs で書けるようにする。
- 移動は 1 ファイルあたりコピーと削除が 2 組(quarantine は理由のファイルを足して 5 回)の書き込みになる。日次のファイルの数では問題にならない。
- 契約(`contracts/files/*.yaml`)と `FileLayout` の一致は、利用者のテストで守る(実行時に契約のファイルを読まない)。
- AWS SDK の範囲を限ったので、新しいモジュールが S3 を使うときは file-transfer の置き場を使う。audit のアンカーは Object Lock の操作(Legal Hold・保持期間)が要るので、例外として残した。
