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

### 6. AWS SDK・Parquet・Hadoop を使ってよい範囲
- AWS SDK(`software.amazon.awssdk`)を本番コードで使ってよいのは、`platform/file-transfer` と `platform/audit` のアンカー(Object Lock。ADR-0017)だけ。ほかは file-transfer の置き場を使う(#12 のコメント)。テストのソースセット(バケットの作成など)は除く。
- Parquet(`org.apache.parquet`)と Hadoop(`org.apache.hadoop`)は `platform/file-transfer` だけ(計測の `tools/parquet-bench` を除く。ADR-0033 §3)。
- 検査は 3 つ。
  - Konsist の import・完全修飾名の検査(`awsSdkOnlyInAllowedModules`・`parquetOnlyInFileTransfer`)。
  - 依存の宣言の検査(`LibraryDeclarationRules`): `libs.aws.*` を本番の設定(`api`・`implementation`・`compileOnly`・`runtimeOnly`)で宣言してよいのは file-transfer と audit だけ。`libs.parquet.*`・`libs.hadoop.*` は file-transfer だけ。Hadoop は `compileOnly` だけ。テストの設定は除く。
  - 実行時のクラスパスに Hadoop がないこと(`platform/file-transfer` の `verifyNoHadoopAtRuntime`。`check` に含める)。

### 7. 監視と運用(⑤・⑥)
- 不合格の数(理由ごと)・移動の失敗・受け口に残っている時間をメトリクスにし、アラートと Runbook を ⑤・⑥ で足す。ラベルにファイル名は入れない(系列が増え続けるため)。

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
- PGP のテストは送り手・受け手とも Bouncy Castle で行った。形式は標準の OpenPGP(v4 の鍵・SEIPD)なので GnuPG と相互に読めるはずだが、GnuPG との相互運用は、⑥ で開発用の鍵を作るときに確かめる。

## 改訂履歴
- 2026-10-15: P09 ③b で、SFTP と PGP(§8)を決めた。③a で書いた方針(sshj・ホスト鍵の固定・古い方式を使わない・暗号化と署名)を、使う暗号の方式の一覧・鍵の扱い・失敗の分け方・統合テストまで具体にした。
