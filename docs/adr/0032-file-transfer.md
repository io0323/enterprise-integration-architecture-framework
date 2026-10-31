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

S3 の部分は P09 ③a、SFTP と PGP(§8)は ③b で決めた。Parquet の書き出しの手段は ADR-0033。

## Decision
### 1. モジュールと置き場
- `platform/file-transfer` を作る。置くもの: S3 互換ストレージの置き場(`ObjectReader` / `ObjectStore`。実装は `S3ObjectStore`)、マニフェスト(`FileManifest`)、データファイルの形(`FileLayout`・`FileName`)、書き出し(`FilePublisher`)、検査(`FileVerifier`)、移動(`FileMover`)、PGP(`PgpKeys`・`PgpFiles`)、SFTP の送信(`SftpSender`)(§8)。
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
- **file-exchange での実装(P09 ⑤a)**
  - 受け取りは DAG `file-exchange.receive`(パーティションはファイル名)。受け口のマニフェストの到着で起動し、**データファイルがなくても起動する**(`Trigger.FileArrival(requireDataFile = false)`。ADR-0030 の改訂履歴)。欠損は検査が `DATA_MISSING` にして quarantine に送る。マニフェストは最後に置く完了の合図なので、マニフェストがあるのにデータがないのは欠陥。
  - 表 `exchange.received_file`(主キーはファイル名)。状態は `MOVING`(移動の決定を記録した) → `STORED`(移し終えた)。記録がなければ検査し、決定を `MOVING` で記録(`INSERT ... ON CONFLICT DO NOTHING`。先に記録した決定に従う)してから移す。記録が `MOVING` なら検査をせずに続ける。
  - **受け取り済みの名前でまた届いたファイル**は、その名前の実行がすでに成功しているので、ファイルの到着の起動は動かない。serve の中の **見回り**(既定 5 分ごと。`sweep` のコマンドでも 1 回)が扱う:
    - 同じ SHA-256 なら `DUPLICATE`(送り直し)、違う・読めないなら `NAME_REUSED`(名前の使い回し)として、`{quarantine}extra/{番号}/` に移す(前に届いたもの・quarantine の同じ名前を上書きしない)。取り込みも取引先への送信もしない。
    - 処理の記録は表 `exchange.extra_arrival`。**同じ名前で処理中(`MOVING`)のものは 1 つだけ**(部分一意索引)。見回りが落ちたら、次の見回りが処理中のものを先に終える。
  - **見回りと受け取りの実行が同じファイルを扱わない仕組み**(承認の条件):
    1. 見回りは、受け取りの実行と同じ **ファイルの名前ごとの advisory lock**(DAG とパーティションのロック。ADR-0030 §2)を取れた名前だけを扱う。受け取りの実行の最中なら飛ばす(次の見回りで見る)。
    2. ロックの中で `received_file` を読み、**`STORED` の名前だけ** を扱う。記録がない・`MOVING` の名前は受け取りの実行のもの。受け取りの実行は `STORED` の名前を移さない。
    3. ロックの中で受け口のマニフェストを **読み直し**、なければ扱わない(一覧の後に、受け取りの実行が移し終えたもの)。
    - 統合テスト(`FileExchangeIT`)で、届いたばかりのファイルに対して見回り 3 つと受け取りを同時に動かすことを 10 回繰り返し、見回りが一度も扱わないこと、受け取り済みの名前で届いたファイルを見回り 4 つが同時に扱っても 1 度だけ移すことを確かめた。
  - 受け口に残っているファイルの数と、最も古いマニフェストの経過時間をメトリクス(`eia.file_exchange.inbox.*`)にし、滞留のアラートは ⑥ で足す。

### 6. AWS SDK・Parquet・Hadoop を使ってよい範囲
- AWS SDK(`software.amazon.awssdk`)を本番コードで使ってよいのは、`platform/file-transfer` と `platform/audit` のアンカー(Object Lock。ADR-0017)だけ。ほかは file-transfer の置き場を使う(#12 のコメント)。テストのソースセット(バケットの作成など)は除く。
- Parquet(`org.apache.parquet`)と Hadoop(`org.apache.hadoop`)は `platform/file-transfer` だけ(計測の `tools/parquet-bench` を除く。ADR-0033 §3)。
- 検査は 3 つ。
  - Konsist の import・完全修飾名の検査(`awsSdkOnlyInAllowedModules`・`parquetOnlyInFileTransfer`)。
  - 依存の宣言の検査(`LibraryDeclarationRules`): `libs.aws.*` を本番の設定(`api`・`implementation`・`compileOnly`・`runtimeOnly`)で宣言してよいのは file-transfer と audit だけ。`libs.parquet.*`・`libs.hadoop.*` は file-transfer だけ。Hadoop は `compileOnly` だけ。テストの設定は除く。
  - 実行時のクラスパスに Hadoop がないこと(`platform/file-transfer` の `verifyNoHadoopAtRuntime`。`check` に含める)。

### 7. 監視と運用(⑤・⑥)
- 不合格の数(理由ごと)・移動の失敗・受け口に残っている時間をメトリクスにし、アラートと Runbook を ⑤・⑥ で足す。ラベルにファイル名は入れない(系列が増え続けるため)。
- **ローカル基盤(P09 ⑥)**
  - バケット `eiaf-file-exchange`(Object Lock なし)と identity は、書き手 `eiaf-file-batch-etl`(受け口 `inbox/sales-daily/` への Write だけ)と、
    受け手 `eiaf-file-exchange`(Read / List / Write)。SeaweedFS の Write にはバケットの管理操作が含まれうるので、**バケットポリシーの Deny で、
    両方の identity のポリシーの変更・削除、バケットの削除、バージョニング・Object Lock・ライフサイクル・ACL の変更を拒否する**(ADR-0017 §7 と同じ)。
    書き手は受け口のファイルも消せない(受け手が受け取る前に消さない)。`make verify PROFILE=file` が両方の identity で拒否されることを確かめる(承認の条件 1)。
  - seaweedfs は batch の profile でも動かし、batch-etl はいつも売上ファイルを書く。**置き場が止まっても、売上ファイルの DAG が失敗するだけで、
    注文の取り込みの DAG とその SLA のメトリクスには影響しない**(別の DAG。`BatchEtlAppIT` で、置き場に接続できない状態で確かめた。承認の条件 2)。
  - 取引先の代わりの `sftp-b2b` のホスト鍵は `make env` が作り、起動時に入れる。file-exchange と verify は known_hosts でこの鍵に固定する(初めての接続で覚えない)。
  - 開発用の PGP の鍵は GnuPG(`infra/local/images/gnupg`。ベースは images.env の `DEBIAN_IMAGE`、パッケージは Debian の版で固定)で作る。
    `make verify PROFILE=file` が、取引先の側として届いたファイルを GnuPG で復号し、署名と改竄の検知(MDC)を確かめる。
    **GnuPG で作った鍵を Bouncy Castle が読み、Bouncy Castle で暗号化・署名したファイルを GnuPG が読めることを確かめた**(§8 の Consequences の持ち越し)。
    Debian の古い版はリポジトリから消えるので、定期確認(pin-check。#35)が固定した版を確かめる(承認の条件 3)。
  - アラート(`prometheus/rules/file-exchange.rules.yml`): quarantine に移した(理由ごと)・名前の使い回し・受け口の滞留(1 時間)・配送の失敗(取引先ごと)・
    取引先の SFTP の Circuit Breaker の Open(5 分)。ダッシュボード **File — Sales daily**。手順は `docs/runbooks/file-exchange.md`。

### 8. SFTP と PGP(P09 ③b)
#### SFTP のクライアント(`SftpSender`)
- ライブラリは **sshj 0.41.2**(クライアントだけで小さく、依存が Bouncy Castle で既存と同じ系統。Alternatives)。
- **使う暗号の方式は次だけに絞る**(`SftpAlgorithms`。sshj の既定は SHA-1 の `ssh-rsa`・`diffie-hellman-group1-sha1`・`arcfour`・CBC・`hmac-md5`・`ssh-dss` まで含むため)。相手がどれも持たなければ接続しない。

  | 種類 | 方式(優先の順) |
  |---|---|
  | 鍵交換 | `curve25519-sha256`、`curve25519-sha256@libssh.org`、`ecdh-sha2-nistp521`、`ecdh-sha2-nistp384`、`ecdh-sha2-nistp256`、`diffie-hellman-group18-sha512`、`diffie-hellman-group16-sha512`、`diffie-hellman-group14-sha256`(と拡張の通知 `ext-info-c`。RFC 8308) |
  | ホスト鍵・クライアントの鍵 | `ssh-ed25519`、`ecdsa-sha2-nistp521`、`ecdsa-sha2-nistp384`、`ecdsa-sha2-nistp256`、`rsa-sha2-512`、`rsa-sha2-256` |
  | 暗号 | `aes256-gcm@openssh.com`、`aes128-gcm@openssh.com`、`aes256-ctr`、`aes192-ctr`、`aes128-ctr` |
  | MAC | `hmac-sha2-512`、`hmac-sha2-256` |

  - 使わないもの: SHA-1 を使う方式(`ssh-rsa`・`diffie-hellman-group1-sha1`・`group14-sha1`・`group-exchange-sha1`・`hmac-sha1`)、`ssh-dss`、CBC・`arcfour`・`3des`・`blowfish` などの古い暗号、`hmac-md5`、サーバが群を選ぶ `diffie-hellman-group-exchange-sha256`。
  - **sshj は strict kex(Terrapin 攻撃 CVE-2023-48795 の対策 `kex-strict-*`)に対応していない**。そのため、Terrapin の影響を受ける `chacha20-poly1305@openssh.com` と EtM の MAC(`*-etm@openssh.com`)も使わない。sshj が strict kex に対応したら、この一覧を見直す。
  - ホスト鍵は known_hosts で固定するので、証明書(`*-cert-v01@openssh.com`)と FIDO の鍵(`sk-*`)は使わない。
- **ホスト鍵は事前に登録したものだけを受け付ける**(`KnownHosts`)。
  - 登録は OpenSSH の known_hosts の形の行(`host keytype base64`。host は `name`・`[name]:port`・カンマ区切り)で、`SecretProvider` から読む(秘密ではないが、鍵と同じく差し替えるため。`NAME_FILE` でファイルを渡せる)。
  - 登録のないホストには接続しない(`CredentialRejected`。接続の前に止める)。登録と違う鍵のホストは拒否する(`RemoteRejected("HostKeyMismatch")`。リトライしない。なりすましの可能性があるので運用に引き継ぐ)。初めての接続で鍵を覚える方式(TOFU)と、検証を外す設定は作らない。
  - ハッシュした host(`|1|`)と、マーカー(`@revoked`・`@cert-authority`)の行は、読み違いを避けるため設定の誤りにする。
  - 登録の手順(`ssh-keyscan` で取り、フィンガープリントを相手と別の経路で照合する。開発用の SFTP のホスト鍵の登録)は ⑥ の Runbook に書く。
- 認証はクライアントの鍵だけ(OpenSSH の形の秘密鍵。パスフレーズは任意で、`SecretProvider` から読む)。パスワードは使わない。
- 送信は **一時名(`{name}.part`)で書いてから正式名に変える**(Framework 9.2)。正式名がすでにあれば消してから変える(SFTP v3 の rename は上書きできないため。同じファイルの送り直しを想定。消してから変えるまでの間は、相手からは正式名が見えない)。
- **正式名のファイルがあるかを確かめる**(`exists`。P09 ⑤b): 正式名だけを見る(一時名の `.part` は数えない)。送信を冪等にするため、送る前に呼ぶ(下の「取引先への配送」)。
- 失敗の分け方: 接続できない・タイムアウト・切断は `RemoteUnavailable`(リトライ)。ホスト鍵の不一致・方式の交渉の失敗(`NoCommonAlgorithm`)・認証の失敗・権限がない・送り先のディレクトリがないは `RemoteRejected`(リトライしない)。鍵・known_hosts の誤りは `CredentialRejected`。
- 統合テスト(`SftpIT`。images.env の `SFTP_IMAGE` の OpenSSH 10.3):
  - 一時名で書いてから正式名に変わり、送り直しは上書きされ、一時名が残らない。
  - 登録と違う鍵のホスト・登録のないホストに接続せず、何も書かない(承認の条件 3)。
  - **古い方式(`diffie-hellman-group14-sha1`・`ssh-rsa`・`aes128-cbc`・`hmac-sha1`)しか持たないホストに接続しない**。対照として、sshj の既定の設定なら同じホストに接続できることを確かめ、拒否が方式の絞り込みによることを示す。
  - クライアントの鍵が違えば認証の失敗。

#### PGP(`PgpKeys`・`PgpFiles`)
- ライブラリは Bouncy Castle の bcpg 1.86(既存の bcpkix と同じ版。sshj の Bouncy Castle も同じ版にそろえる)。
- **鍵の扱い**(承認の条件 1)
  - 取引先の公開鍵: 送るファイルの **暗号化** と、受け取ったファイルの **署名の検証** に使う。
  - 自社の秘密鍵(とパスフレーズ): 送るファイルの **署名** と、受け取ったファイルの **復号** に使う。
  - どれも ASCII armor の文字列で `SecretProvider` から読む(`PgpKeyNames`)。秘密鍵は **パスフレーズで保護されたものだけ** を受け付ける。読み込み時に、取引先の暗号化の鍵があること・自社の署名の鍵とパスフレーズが正しいことを確かめ、誤りは `CredentialRejected`(鍵とパスフレーズの値は入れない)。
  - ログと運用の照合には、主鍵のフィンガープリントを使う。
  - 鍵の差し替えは、秘密情報のファイルを差し替えて次の送受信から読み直す(`PgpKeys.load` を送受信ごとに呼ぶ)。取引先の鍵を 2 つ並べて受け付ける移行期間の扱いは、必要になったら足す。
- **送る**(`encryptAndSign`): 取引先の公開鍵で暗号化し、自社の秘密鍵で署名する(`gpg --sign --encrypt` と同じ形: 暗号化 → 圧縮 → 一括署名 + 本文 + 署名)。暗号は AES-256 で、改竄の検知の付いた形(SEIPD + MDC)。圧縮は ZLIB。署名のハッシュは SHA-512。出力は ASCII armor にしない(拡張子 `.pgp`)。
- **受け取る**(`decryptAndVerify`): 自社の秘密鍵で復号し、取引先の公開鍵で署名を確かめる。**署名と改竄の検知の両方が確かめられるまで合格にしない**。不合格なら、書き出した本文を消して理由(`PgpRejection`)を返す。
  - 理由: PGP でない・自社あてでない・改竄の検知のない古い形式(SED)・署名がない・取引先の鍵でない鍵の署名・署名の不一致・改竄の検知の不一致・形式の誤り。
- PGP は、取引先への SFTP の経路では必須、社内の S3 の経路では任意(P09 の計画)。どこで使うかは file-exchange(⑤)が決める。
- 単体テスト(`PgpFilesSpec`): 数 MB の往復、改竄、なりすまし(第三者の署名)、自社あてでない暗号文、署名のない暗号文、改竄の検知のない形式、鍵の誤り。統合テスト(`SftpIT`)で、暗号化と署名をして SFTP で送ったファイルを、取引先の鍵で署名を確かめて復号できること、サーバの上で 1 バイト変えると不合格になることを確かめる。

#### 取引先への配送(file-exchange。P09 ⑤b)
- **配送のステップ**: 受け取りの DAG `file-exchange.receive` に、`receive` の後のステップ `deliver` を足した。合格(archive)のファイルを、そのデータセット(`{system}_{dataset}`。名前から時刻と連番を除いたもの)を送る取引先ごとに送る。quarantine に移したファイルは送らない。
  - 1 つの取引先に送れなくても、ほかの取引先には送ってから、ステップを失敗にする(ステップのリトライと、運用者の再実行 `receive <名前>` で、送れなかった取引先だけに送る。受け取りは `STORED` なので何もしない)。
- **取引先の台帳**(承認の条件 2): JSON のファイル(`FILE_EXCHANGE_PARTNERS_FILE`)に、**取引先の ID ごとに** 送るデータセット・送り先(SFTP のホスト・ポート・利用者・ディレクトリ)・ホスト鍵(known_hosts)・クライアントの鍵・PGP の公開鍵を持つ。P12 の B2B Gateway の取引先の台帳に、この形のまま引き継ぐ。
  - 台帳には秘密情報の **名前** だけを書く(値は `SecretProvider` から読む)。自社の PGP の署名の鍵(`FILE_EXCHANGE_PGP_SECRET_KEY`・`_PASSPHRASE`)は取引先によらないので台帳に書かない。
  - 取引先の ID は英小文字・数字・ハイフンの 32 文字まで(メトリクスの属性と Resilience の名前に使う)。台帳の誤り(形・ID の重複・鍵の名前の形)は起動時に設定の誤りにする。台帳がなければ配送しない。
  - ローカルでは、取引先の代わりに `sftp-b2b`(b2b プロファイル。利用者 `partner01`・`/config/outbox`)に送る(⑥ で compose に登録する)。
- **配送の記録**: 表 `exchange.delivery`(主キーはファイル名と取引先の ID の組)。状態は `SENDING` → `SENT`。送信済み(`SENT`)の組には何もしない。
- **送信の冪等**(承認の条件 4): 正式名に変えた後、送信済みを記録する前に落ちると、やり直しで同じファイルを送り直してしまう。そこで、**送る前に取引先の側に同じ正式名のファイルがあるかを確かめ(`exists`)、あれば送らずに送信済みにする**(`already_present = true`)。
  - 手順: 送信中を記録 → 正式名があるかを確かめる → なければ archive から読み出し、暗号化と署名をして送る(一時名 → 正式名) → 送信済みを記録。
  - 一時名(`.part`)だけが残っている(書いている途中で落ちた)なら、正式名はないので送り直す(一時名は上書きされ、正式名に変わる)。
  - 相手の側の正式名は、データファイルの名前に `.pgp` を付けたもの。同じ名前のファイルの中身が同じかは確かめない(暗号文は暗号化のたびに変わるため。名前は時刻と連番で一意)。
  - 限界: 取引先が、送信済みの記録の前に正式名のファイルを取り込んで消していれば、正式名がないので送り直す。取引先の側でファイル名による重複の検知が要る(取引先との取り決めに書く)。
- **Timeout・Retry・Circuit Breaker**(承認の条件 3。外への最初の同期の呼び出し): SFTP の呼び出し(確かめる・送る)は、**取引先ごとの `Resilience`(名前 `sftp-{取引先の ID}`)** で包む。app で `ResilienceMetrics.resilience(...)` から作る(Konsist の `resilienceOnlyThroughMetrics`)。
  - 設定: 試行の上限 2 分(sshj の接続 10 秒・応答の待ち 30 秒に、送る時間を足した)、呼び出し全体の締め切り 5 分。Retry と Circuit Breaker は既定(初回を含めて 3 回、500ms から。直近 10 回のうち 50% の失敗で 30 秒止める)。
  - リトライするのは `RemoteUnavailable`(接続できない・タイムアウト・切断)だけ。ホスト鍵の不一致・認証の失敗などはリトライせず、Circuit Breaker にも数えない。
  - archive の読み出しと暗号化は Resilience の外で行う(置き場の不調を、取引先の Circuit Breaker に数えない)。sshj の呼び出しはブロックするので、割り込みできる IO のスレッドで呼ぶ(試行のタイムアウトで止める)。
  - Fallback は持たない: 送れなければ配送を `SENDING` のまま残してステップを失敗にし、再実行で続ける(送ったことにする代替の動作はない)。
- **メトリクス**: `eia.file_exchange.deliveries`(属性 `file_exchange.partner`・`file_exchange.outcome` = `sent` / `already_present` / `already_sent` / `failed`)。Resilience のメトリクス(`eia.resilience.*`)は依存先の名前 `sftp-{取引先の ID}` で出る。アラートは ⑥ で足す。
- **統合テスト**
  - `DeliveryIT`(PostgreSQL・SeaweedFS・SFTP のサーバの前に Toxiproxy):
    - 送ったファイルを、取引先の鍵で署名を確かめて復号でき、中身が archive と同じ。やり直しは `already_sent`。
    - 送信中の記録があり、取引先の側に正式名がある(前の実行の暗号文を置く)なら、送らずに送信済みにし、相手の側のファイルが前の実行のまま変わらない(承認の条件 4)。
    - 一時名だけが残っていれば、送り直して正式名にする。
    - Toxiproxy で接続を切ると、リトライして Circuit Breaker が開き、Open の間は接続せずに `circuit_open` を返す。接続を戻すと、Open の期間の後に Half-Open を経て閉じ、送り終える。**`InMemoryMetricReader` で、`eia.resilience.retries` と `eia.resilience.circuit_breaker.transitions`(closed→open・open→half_open・half_open→closed)が依存先の名前 `sftp-partner01` で出ることを確かめた**(P05 の #8-c の持ち越し)。
  - `FileExchangeAppIT`: 台帳を渡した `receive` で、受け取りの後に取引先の SFTP に `.pgp` が届き、配送の記録が `SENT` になる。台帳の誤りは設定の誤り(終了コード 2)。

#### 配置
- sshj(`net.schmizz`・`com.hierynomus.sshj`)と OpenPGP(`org.bouncycastle.openpgp`・`org.bouncycastle.bcpg`)を本番コードで使ってよいのは `platform/file-transfer` だけ(テスト用の鍵を作る `platform/test-support` を除く)。方式の絞り込みとホスト鍵の検証を各所で書き直させないため。Konsist(`transferSecurityOnlyInFileTransfer`)と依存の宣言の検査(`LibraryDeclarationRules`)で守る。

## Alternatives Considered
| 案 | 採らない理由 |
|---|---|
| S3 でも一時名で書いて、コピーと削除で正式名にする | 名前の変更がないので、コピーと削除が増え、その間で落ちたときの扱いも増える。S3 の PUT は置き終わるまで見えないので、正式名で 1 回置いてマニフェストを最後に置けば、Framework 9.2 の完了の合図を満たす |
| マニフェストを先に、データファイルを後に移す | 受け口にデータファイルだけが残ったとき、起動の合図(マニフェスト)がないので、移動の続きが起動されない |
| 受け取りの記録を S3 の archive/ の中身(一覧)で代える | §5。保存期間で消える・移動中と済みを区別できない・一意の制約がない |
| 検査の中で移動まで行う(1 つの API) | §4 の危険な落ち方で、やり直しが検査から始まり、合格したファイルを quarantine に送る。決定の記録を呼び出し側(DB)が持てない |
| SFTP のクライアントに Apache MINA SSHD(2.20.0)を使う | クライアントとサーバの両方を持つ大きなライブラリで、使わない部分が多い。暗号の方式の絞り込みとホスト鍵の検証は sshj でも同じようにできる。3.0 は 2026-10 の時点でマイルストーン版 |
| SFTP のホスト鍵を初めての接続で覚える(TOFU)・検証しない設定を用意する | 初めての接続がなりすましなら、その鍵を信用してしまう。検証を外す設定は、一度作ると本番に紛れ込む(承認の条件 3) |
| PGP の出力を ASCII armor にする | 大きさが約 4/3 になる。取引先との取り決めで必要になったら、設定で選べるようにする |
| 受け側で Parquet の全部の行を読んで検査する | 改竄と欠損は SHA-256 と件数で足りる。全部を読むと、読み手に Hadoop が要る(ADR-0033 §2)。合計値のコントロールレコード(Framework 9.2)が要るときに改訂する |

## Consequences(トレードオフ)
- 受け取りの手順(決定の記録 → 移動 → 済み)は file-exchange の責任になる。③a の部品だけを使って、記録なしで検査と移動を続けて呼ぶと、§4 の危険な落ち方で合格したファイルを失う。部品のドキュメント(`FileMover`)にこの前提を書いた。
- データファイルはローカルの一時ファイルを経由する(書き出し・検査とも)。1 ファイルの大きさだけ `/tmp` の空きが要る(100 万件の Parquet で約 28 MB。ADR-0033 のレポート)。ルートのファイルシステムを読み取り専用にするときは `/tmp` を tmpfs で書けるようにする。
- 移動は 1 ファイルあたりコピーと削除が 2 組(quarantine は理由のファイルを足して 5 回)の書き込みになる。日次のファイルの数では問題にならない。
- 契約(`contracts/files/*.yaml`)と `FileLayout` の一致は、利用者のテストで守る(実行時に契約のファイルを読まない)。
- AWS SDK の範囲を限ったので、新しいモジュールが S3 を使うときは file-transfer の置き場を使う。audit のアンカーは Object Lock の操作(Legal Hold・保持期間)が要るので、例外として残した。
- SFTP の暗号の方式を絞ったので、古い方式しか持たない取引先とは接続できない。その取引先と接続するときは、方式を足すのではなく、相手に更新を頼む(足すなら、この ADR を改訂して理由を残す)。
- 送信の冪等は、取引先の側に正式名のファイルが残っていることに頼る。取引先が受け取ってすぐに消すと、落ちた後のやり直しで送り直すことがある(§8 の限界)。
- 取引先ごとに Resilience を作るので、取引先の数だけ Resilience のメトリクスの系列が増える(取引先の数は台帳で決まり、少ない)。
- PGP のテストは送り手・受け手とも Bouncy Castle で行った。GnuPG との相互運用は、⑥ で開発用の鍵を GnuPG で作り、`make verify PROFILE=file` で確かめた(§7)。
- 開発用の GnuPG のイメージは Debian のパッケージを版で固定したので、Debian のポイントリリースで版が消えると組み立て直せない。定期確認で検知して版を上げる(§7)。

## 改訂履歴
- 2026-10-15: P09 ③b で、SFTP と PGP(§8)を決めた。③a で書いた方針(sshj・ホスト鍵の固定・古い方式を使わない・暗号化と署名)を、使う暗号の方式の一覧・鍵の扱い・失敗の分け方・統合テストまで具体にした。
- 2026-10-16: P09 ⑤a で、file-exchange の受け取りの実装(§5: 受け取りの DAG・状態・見回り・見回りと受け取りの排他)を書いた。理由のコードに `duplicate`・`name_reused` を足した。
- 2026-10-17: P09 ⑤b で、取引先への配送(§8「取引先への配送」: 配送のステップ・取引先の台帳・配送の記録・送る前の正式名の確認による冪等・取引先ごとの Resilience)を書いた。`SftpSender` に正式名の確認(`exists`)を足した。
- 2026-10-18: P09 ⑥ で、ローカル基盤(§7: バケットと identity とバケットポリシー・置き場の停止と取り込みの独立・ホスト鍵の固定・GnuPG の鍵と相互運用・アラート・ダッシュボード・Runbook)を書いた。
- 2026-10-31: P12 ③ で、取引先の台帳を b2b-gateway と共有する 1 つの台帳(`infra/local/b2b/partners.json`。読み込みは `platform/trading-partner`)に移した(ADR-0040 §2)。§8 の台帳の項目は、送るデータセットを `documents`(連携カタログの ID・向き・データセット)に移し、識別子・鍵の期日・合意・連絡先を足した形になった。file-exchange の `PartnerRegistry` は、台帳からファイルの送り先(`PartnerDestination`)を作る変換になった。取引先への送信は P12 ⑦ で b2b-gateway に移す。
- 2026-10-31: P12 ④a で、`PgpKeys` に取引先の旧い公開鍵(署名の検証にだけ使う)を持たせ、署名の鍵の差し替えの間は新旧の両方の署名を受け付けるようにした(`PgpVerification.Verified` は実際に署名した鍵のフィンガープリント。ADR-0040 §8)。原本の保管のために、Object Lock(COMPLIANCE)で置く `LockedObjectWriter`(`S3ObjectStore.putLocked`)を足した。
