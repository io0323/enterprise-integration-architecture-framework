# ADR-0017: 監査記録のハッシュチェーン・アンカー・audit 専用の S3 資格情報
- Status: Accepted
- Date: 2026-09-29
- Framework 参照章: 12, 14.1
- 関連: ADR-0008(Audit の縮退)、ADR-0015(SeaweedFS・path-style)、ADR-0016 §5・§6(Testcontainers のイメージ、資格情報)、ADR-0018 §3(マスキング)、ADR-0019 §6(SecretProvider)
- 番号: P04a のサブ PR ごとに予約した番号(0017 audit / 0018 observability / 0019 security)。マージの順で一時的に番号が飛んでいた

## Context
P04a ④ で `platform/audit` を作る。Framework 14.1 は「誰が・いつ・何を・どこへ」を改竄不能なストレージに記録することを求めている。ADR-0008 は、ローカルでは次の構成に縮退すると決めた。

- PostgreSQL の追記専用テーブルに、前の記録のハッシュを含めたハッシュチェーンで記録する。
- 日次でチェーンの先頭を、SeaweedFS の Object Lock(COMPLIANCE)に保存する。

決める必要があるのは次の点。

1. 記録する項目と、ペイロードの扱い
2. テーブルの置き場所と権限(改竄をどこまで防ぎ、どこから検出に任せるか)
3. ハッシュを計算するための直列化の形式と、その版の管理
4. 並行して追記したときのチェーンの一貫性と、性能への影響
5. アンカーの形式・保持期限・S3 クライアント
6. 改竄の検査の方法と結果の返し方
7. audit 専用の S3 の資格情報の範囲(Issue #6 のコメント。PR #28 のレビューの Major 3)

## Decision

### 1. 記録する項目: ペイロードの本体は記録しない
| 区分 | 列 | 内容 |
|---|---|---|
| 誰が | `actor_type` / `actor_id` | `user` / `service` / `partner` / `system` と、その ID |
| いつ | `occurred_at` / `recorded_at` | 業務の事象の時刻 / 記録した時刻(UTC、マイクロ秒に切り捨て)。`recorded_at` はチェーンのロックを取った後に読むので、`seq` の順と揃う(時計が戻らない限り) |
| 何を | `action` / `target_type` / `target_id` / `outcome` | 例 `order.create` / `order` / `ord-123` / `success`・`failure`・`denied` |
| どこへ | `destination` | 例 `kafka:sales.order.created.v1`・`partner:acme`。送り先がなければ NULL |
| 本文の代わり | `payload_sha256` / `payload_ref` | ペイロードの SHA-256 か参照キーだけ。本文を入れる列は作らない |
| 追跡 | `correlation_id` / `traceparent` | ADR-0018 と同じ値 |
| 補足 | `details`(jsonb) | 文字列か NULL の値だけ。**値は保存する前に必ず `Masking.mask` を通す**(ADR-0018 §3)。キーは `^[a-z][a-z0-9_.-]{0,63}$`、32 件まで、伏せた後の値は 1,024 文字まで |

- 識別子の欄(actor・target・destination・payload_ref)は伏せない。制御文字と長さだけを検査する(256〜1,024 文字)。ここに個人情報や本文を入れないのは、呼び出し側の責務とする(参照キーを入れる)。
- 違反は `InvalidAuditEvent` で返し、DB には触れない。
- `actor_id` などは個人データになりうる。`audit.audit_log` の機密区分と保持期間は、利用するサービスのカタログ(`contracts/catalog/*.yaml`)に記載する(P05 以降。Framework 16)。

### 2. テーブルと権限: 各サービスの DB に置き、チェーンはサービスごとに独立させる
- テーブルは、各サービスの DB のスキーマ `audit` に `audit.audit_log` として作る。
  - マイグレーション(`db/audit/V1__audit_log.sql`)は `AuditSchema.migrate(dataSource, appRole)` で、DB の所有者のロールで適用する。
  - Flyway の履歴テーブルは、サービスのマイグレーションとは別の `audit.audit_schema_history` を使い、版の番号がぶつからないようにする。
- **ロールを分ける**。
  - 所有者(例 `order_service`): マイグレーションを実行する。
  - アプリ用(`{name}_app`。例 `order_service_app`): `audit.audit_log` には `INSERT` と `SELECT` だけを付ける。`UPDATE` / `DELETE` / `TRUNCATE` は付けない。
  - ローカル基盤では、`postgres/init/10-service-databases.sh` がサービスの DB ごとにアプリ用のロールを作る(`LOGIN` と `CONNECT` だけ。表の権限は各マイグレーションが付ける)。初期化スクリプトは空のボリュームでしか動かないため、**既存のボリュームでは `make clean` が必要**(`make verify PROFILE=core` がロールの有無を検査して案内する)。
- **トリガーでも拒否する**。`BEFORE UPDATE OR DELETE`(行単位)と `BEFORE TRUNCATE`(文単位)のトリガーが、SQLSTATE 42501 で例外にする。権限を持つ所有者の誤操作も止める。
- トリガーを外せるロール(所有者・superuser)による改竄は防げない。ハッシュチェーンとアンカーで**検出する**(§6)。

### 3. 直列化の形式(`canonical_version` = 1)
記録を必ず同じバイト列に直してから SHA-256 を取る。形式は版で固定し、`canonical_version` を列に持ち、直列化の入力にも含める。検証は、記録ごとの版で直列化の方法を切り替える(`CanonicalForms.forVersion`)。形式を変えるときは、版 1 を変えずに新しい版を足す。

**版 1 の仕様**(`CanonicalFormV1`)

| 順 | 要素 | 表し方 |
|---:|---|---|
| — | 接頭辞 | ASCII の `EIAF-AUDIT`(10 バイト。ほかの用途のバイト列と取り違えないため) |
| — | 版 | u16、ビッグエンディアン(`0x0001`) |
| 1 | `seq` | 欄 = 8 バイトの符号付き整数(ビッグエンディアン) |
| 2 | `prev_hash` | 欄 = 小文字の 16 進数 64 文字の UTF-8 |
| 3 | `occurred_at` | 欄 = UTC の 1970-01-01T00:00:00Z からのマイクロ秒(8 バイトの符号付き整数)。マイクロ秒未満は負の無限大の向きに切り捨てる |
| 4 | `recorded_at` | 同上 |
| 5〜15 | `actor_type`, `actor_id`, `action`, `target_type`, `target_id`, `destination`, `outcome`, `payload_sha256`, `payload_ref`, `correlation_id`, `traceparent` | 欄 = UTF-8(Unicode の正規化はしない。保存された値のまま) |
| 16 | `details` | 有無のフラグ(常に `0x01`)+ 件数(u32)の後に、キーの UTF-8 のバイト列の**符号なしの辞書順**で「キーの欄・値の欄」を交互に置く。キーは常に値あり、値は NULL がありうる |

- **欄** = 有無のフラグ(u8。`0x00` = NULL、`0x01` = 値あり)+ 値ありのときだけ「長さ(u32、ビッグエンディアン、バイト数)+ 値のバイト列」。**NULL と空文字列(長さ 0)を区別する**。長さを前に置くので、欄の境界をずらした値も同じバイト列にならない。
- `hash` 列の値そのものは入力に含めない。
- **時刻の精度**: PostgreSQL の timestamptz はマイクロ秒で、入力のマイクロ秒未満を丸める。そのため、記録する側がマイクロ秒に切り捨ててから保存し、保存された値とハッシュの入力を一致させる。
- **形式の固定**: `CanonicalFormV1Spec` に、固定の入力に対して期待するバイト列(253 バイト)と SHA-256 を置いている。期待値は、Kotlin の実装とは別に、この表から Python で組み立てた。実装を変えて形式が変われば、このテストが失敗する。

### 4. ハッシュチェーンと追記の直列化
- `hash = SHA-256(版 1 のバイト列)`。先頭の記録の `prev_hash` は 64 個の 0。
- 追記(`AuditLog.append`)は、業務の更新と**同じトランザクション**で行う。業務がロールバックすれば記録も残らないので、`seq` に欠番はできない。Exposed のトランザクションからは `appendAudit(transaction, event)` を使う。
- `pg_advisory_xact_lock(0x4549414641554449)`(ASCII の `EIAFAUDI`)でチェーンへの追記を直列にし、ロックを取った後に末尾(`seq` と `hash`)を読んで次の `seq` を決める。ロックはコミットかロールバックで外れる。DB ごとにチェーンは 1 本なので、キーは定数でよい。
- 次の呼び出し方は、誤りとして `AuditMisuse` で拒否する。
  - **自動コミットが有効**: ロックが文ごとに外れ、チェーンが分岐する。
  - **分離レベルが READ COMMITTED でない**: REPEATABLE READ 以上では、ロックを取る前のスナップショットで末尾を読むため、直前にコミットされた記録が見えず、`seq` の一意制約の違反になる。
- **性能の目安**(詳細は `docs/reports/p04a-audit-throughput.md`)
  - **ローカルの開発機での目安であり、本番の性能値ではない。**
  - 計測環境: Apple M3、Docker に割り当てた CPU 8・メモリ 7.75 GiB、PostgreSQL 18.6(既定の設定)。
  - 結果: 1 つのサービスの DB で、**毎秒 1,000〜2,000 件程度**。同時実行数を 16 まで増やしても、毎秒 2,000 件前後で頭打ちになる。ロックのない単純な INSERT(毎秒 3,000〜14,000 件)の 15〜45%。
  - 頭打ちの理由: 追記を直列にしたため。計測中、ロックを待つ接続の数の平均は、同時実行数 4 で 2.4、16 で 14.3 だった。
  - 最初の計測で同時実行数 4 だけが毎秒 695 件と低かった。しかし、再計測の 6 回(1,288〜2,175 件/秒)とロックの待ちの量から、ばらつきによる外れ値と判断した。
  - ロックは業務のトランザクションのコミットまで持たれる。監査を記録するトランザクションは短く保つ(外部の呼び出しをトランザクションの中で行わない)。

### 5. アンカー: チェーンの先頭を S3 の Object Lock(COMPLIANCE)に保存する
- `AnchorPublisher.publish` は、末尾の記録の `seq` と `hash` を、次の形式で `anchors/{service}/{date}.json` に保存する。
  ```json
  {"format":"eiaf.audit.anchor.v1","service":"order","seq":123,"hash":"<64 桁>","canonical_version":1,"created_at":"2026-09-28T23:59:59.9Z"}
  ```
  - 記録が 1 件もなければ保存しない。
  - スケジュール(日次など)への結線は各サービス(P05 以降)で行う。
- **`{date}` は UTC の日付**で区切る(サービスの場所や夏時間によらず、キーが一意に決まる)。
- **同じ日付のキーに 2 回以上保存してよい**。バケットのバージョニングにより、上書きされず版として残る。検証は全版を照合する(§6)。
- **COMPLIANCE と保持期限は、put のたびに明示する**(`x-amz-object-lock-mode` / `x-amz-object-lock-retain-until-date`)。バケットの既定の保持設定には頼らない。既定の保持設定を GOVERNANCE に書き換えても、保存したアンカーが COMPLIANCE になることを、統合テストで確かめた。
- **保持期間は設定値にする**(`AnchorPublisher` の `retention`)。
  - **ローカル基盤の既定値は 1 日**(`make audit-verify` の既定の最小値 `AUDIT_MIN_RETENTION=P1D` と同じ)。COMPLIANCE は管理者でも解除できず、ボリュームを消す(`make clean`)まで残るため、短くした。
  - 統合テストでは、オブジェクトごとの保持期限を 3 分先にする。
  - **本番の保持期間は環境ごとに設定で決める。** 法令(監査証跡の保存義務)と社内規程で決まり、このリポジトリでは決めない。既定値の 1 日は、ローカルで検証を繰り返すための値で、本番の根拠にはならない。
- **S3 クライアントは AWS SDK for Java v2**(`s3` + `url-connection-client`)。
  - HTTP の実装は JDK の `HttpURLConnection` で、Netty・Apache HTTP Client・CRT を持ち込まない。
  - addressing style は設定で切り替え、既定は path-style(ADR-0015 §2)。
  - **SDK の既定のチェックサムの計算は使わない**(`requestChecksumCalculation` / `responseChecksumValidation` = `WHEN_REQUIRED`)。S3 互換ストレージで、既定の CRC 系のチェックサムが原因で失敗するのを避けるため。
  - 代わりに、Object Lock つきの put には `Content-MD5` と `x-amz-checksum-sha256` を自分で計算して付ける(S3 は Object Lock つきの put にチェックサムを求める)。付くことは、統合テストで送信するヘッダを記録して確かめた。
  - 資格情報は、呼び出しのたびに `SecretProvider` から取る(ADR-0019 §6。ローテーションに追従する)。
  - エラーの理由には、S3 のエラーコードだけを入れる。
  - **同期呼び出しの 4 点セット(Framework 13)の扱い**
    - Timeout: 接続 5 秒、呼び出し全体 30 秒を明示する。
    - Retry: SDK の既定の再試行に任せる。
    - Circuit Breaker と Fallback は付けない。アンカーの保存は日次などのバッチの処理で、利用者の要求の経路にないため。失敗は `AuditStorageUnavailable`(Retryable)で返し、呼び出し側のスケジューラが次の回で再実行する。
- **アンカーの JSON は、契約(`contracts/`)には置かない。** このリポジトリの中でだけ書き・読む、ストレージの内部の形式として扱う。形式はこの ADR と `Anchor.FORMAT`(`eiaf.audit.anchor.v1`)で固定する。外部の監査人に渡す形式が必要になったら、`contracts/files/` にスキーマを置いて契約にする。

### 6. 改竄の検査
- **チェーン**(`ChainVerifier`): `seq` の昇順に 1 件ずつ読み(1,000 件ずつのキーセットのページング。全件をメモリに載せない)、次を検出する。
  - `hash_mismatch`: 記録を直列化し直したハッシュが `hash` と一致しない
  - `broken_link`: `prev_hash` が直前の `hash` と一致しない(先頭では 64 個の 0 と比べる)
  - `missing_seq`: 欠番
  - `out_of_order`: 順序の入れ替え・重複
  - `unknown_canonical_version`: 直列化の方法がない版
  - `malformed_record`: 解釈できない行(details が文字列以外を含む、NOT NULL の列が NULL、時刻が `'infinity'` など記録できる範囲の外)
  - `row_count_mismatch`: 表の件数と検証できた件数が一致しない

  - ページングのキーは `(seq, hash)` の組にする。`seq` だけだと、主キーを外して `seq` を重複させた行が、ページの境界で読み飛ばされる。
    - 実行計画(2026-09-29、PostgreSQL 18.6、10 万件): 主キーの索引で `seq >= ?` の範囲を絞り、`(seq, hash)` の並べ替えは Incremental Sort になる。1 ページ(1,000 件)あたり 1〜2 ms で、追加の索引は要らない。
  - `seq` や `hash` を NULL にした行は、行の比較に一致せず読まれない。そのため、走査の後に表の件数(`count(*)`)と照合する。
  - **チェーンの読み込みと件数の照合は、1 つの REPEATABLE READ の読み取り専用トランザクションで行う**(最後に巻き戻す)。別々に読むと、検査の間の追記で件数が合わず、誤って改竄の疑いになる。アンカーはその前に読むので、アンカーの `seq` の記録は必ずスナップショットに含まれる。検査の接続は、自動コミットが有効な専用の接続であること(呼び出し側のトランザクションを巻き戻さないため)。追記を続けながら検査しても誤検知しないことを、`AuditAnchorIT` で確かめた。
  - 直列化できない値(範囲外の時刻)を含む行も、例外で検証を止めずに `malformed_record` にする。

  ある記録で不一致を見つけても、以降は保存された `hash` でつなぐ。1 件の改竄で後続の全件を不一致にせず、改竄された位置を特定するため。
- **アンカー**(`AnchorVerifier`): `anchors/{service}/` の**全版**(削除マーカーを含む)について、次を確かめる。
  - 削除マーカーでない(`anchor_delete_marker`)
  - 保持モードが COMPLIANCE(`anchor_not_compliance`)
  - 保持期限が「ストレージが記録した保存の時刻 + 最小の保持期間」以上(`anchor_retention_too_short`。時計のずれを 5 分許容する)
  - 内容がアンカーの形式で、サービス名とキーの日付(UTC)が一致する(`anchor_invalid`)
  - アンカーの `seq` の記録があり、`hash` が一致する(`anchor_hash_mismatch` / `anchor_record_missing`)

  チェーンだけでは、末尾の記録の書き換え(`hash` の計算し直しを含む)と、末尾からの削除は検出できない。これをアンカーとの照合で検出する。
- **`make audit-verify SERVICE=<name>`**(`tools/audit-verify`)
  - 1 つのサービスについて、上の検査を行う。
  - 終了コード: **0** = 改竄の疑いなし、**1** = 改竄の疑いあり、**2** = 検査を実行できない(設定・接続の失敗、監査テーブルがない)。
  - 想定外の例外もすべて 2 にする。JVM が例外で終わると終了コードが 1 になり、「改竄の疑い」と区別できなくなるため。出力には例外のクラス名だけを出す。
  - 出力には `seq` とアンカーのキーだけを出し、記録の中身と資格情報は出さない。
  - DB にはアプリ用のロール(読み取り専用の接続)で、S3 には検査専用の読み取り専用の資格情報(`eiaf-audit-verify`。§7)で接続する。
  - 起動スクリプトを直接実行して、終了コードを保つ(Gradle の `run` は終了コードを 1 に丸めるため)。
  - 検出したときの対応は `docs/runbooks/audit-verify.md`。

### 7. audit 専用の S3 の資格情報
- **アンカーを書く identity はサービスごとに分ける(Issue #43。2026-10-01 改訂)**: `eiaf-audit-{service}`(例 `eiaf-audit-order`)の操作を、`Read:eiaf-audit` / `List:eiaf-audit` / **`Write:eiaf-audit/anchors/{service}/*`** に限る。資格情報は `{SERVICE}_AUDIT_S3_ACCESS_KEY` / `{SERVICE}_AUDIT_S3_SECRET_KEY`(`make env` が生成)で、`S3AnchorStoreConfig` には既定の名前を置かず、サービスが必ず明示する(取り違えを防ぐ)。管理者の `eiaf` は、バケットとポリシーの作成にだけ使う。
  - SeaweedFS 4.47 で使い捨てのコンテナで確かめた結果(2026-10-01): プレフィックスに限った `Write` は、自分のプレフィックスへの put を許し、**ほかのサービスのプレフィックスと `anchors/` の外への put を拒否する**。さらに、全体の `Write` と違い、**バケットの管理操作(Object Lock の設定・バージョニング・ポリシー・バケットの削除と作成)も拒否する**。自分のプレフィックスの Legal Hold の変更と削除(削除マーカー)は許すので、下のバケットポリシーで拒否する。
  - 比べた方式: 全体の `Write` にバケットポリシーの `NotResource` で自分のプレフィックス以外の put を拒否する方式も効いたが、管理操作は identity の側で絞れない(ポリシーの Deny に頼る)ため、プレフィックスに限った `Write` を採用した。
  - 以前の共有の identity `eiaf-audit`(バケット全体の `Write`。P04a ④。Issue #6)は削除した。アンカーを書くサービスがまだなかったため、移行は不要だった。
- 以下の表は、P04a ④ の時点の共有の identity(バケット全体の `Write`)で確かめた結果。
- **SeaweedFS 4.47 の `Write` は管理操作を含んでいた**。2026-09-28 に使い捨てのコンテナで確かめた結果(AWS CLI 2.37.4)は次のとおり。

  | 操作(audit の資格情報。バケットポリシーなし) | 結果 |
  |---|---|
  | COMPLIANCE つきの put / get / list / 全版の一覧 / 保持の設定の取得 | 許可(必要な操作) |
  | **バケットの Object Lock の設定の変更**(既定の保持設定を GOVERNANCE 1 日にできた) | **許可** |
  | バージョニングの Suspended | 拒否(ただし権限ではなく `InvalidBucketState`。`Status=Enabled` の put は許可) |
  | Legal Hold の変更 | 許可 |
  | 版を指定しない delete(削除マーカーを作る) | 許可(元の版は残る) |
  | バケットポリシーの変更 / バケットの削除・作成 / 保持期限の短縮 / 保持期限内の版の削除 | 拒否(AccessDenied) |
  | ほかのバケットへの list / put / get / delete | 拒否(AccessDenied) |
  | list-buckets | 許可(返るのは `eiaf-audit` だけ) |

- **対策: 書込み用の identity を拒否するバケットポリシー**(`infra/local/seaweedfs/audit-bucket-policy.json`。Principal は `eiaf-audit-order`。サービスを足すときは同じ形で足す。複数の Principal を 1 つの文に並べる形を SeaweedFS が解釈するかは、そのときに確かめる)
  - SeaweedFS 4.47 は、Principal の `{"AWS": "arn:aws:iam::<アカウント>:user/<identity 名>"}` を解釈する。アカウントの部分は `*` でも `000000000000` でも一致した。`"eiaf-audit"`・`{"AWS":"eiaf-audit"}`・`arn:aws:iam:::user/...`(アカウントが空)・アクセスキーでの指定は効かなかった(拒否されない)。
  - 次の 9 つを Deny する: `PutBucketObjectLockConfiguration`・`PutBucketVersioning`・`PutBucketPolicy`・`DeleteBucketPolicy`・`DeleteBucket`・`DeleteObject`・`DeleteObjectVersion`・`PutObjectLegalHold`・`BypassGovernanceRetention`。
  - 設定は `seaweedfs-init`(AWS CLI のイメージ。file / b2b profile)が、管理者の資格情報で `make up` のたびに行う。何度実行しても結果は同じ。バケットは Object Lock を有効にして作り、既定の保持設定は付けない。
  - ポリシーを設定した後の確認(`make verify PROFILE=file` の `s3-audit.sh` と `AuditAnchorIT`):
    - audit の資格情報では、上の 9 つの操作と、ほかのバケットへの list / put / get / delete、バケットの作成が、すべて AccessDenied になる。
    - 管理者は、Object Lock の設定・バージョニング・ポリシー・Legal Hold の変更を続けられる。
    - COMPLIANCE の版の削除と保持期限の短縮(GOVERNANCE への変更、bypass の指定を含む)は、**管理者の資格情報でも拒否される**(ADR-0015 で持ち越した確認)。
- **検査専用の identity `eiaf-audit-verify`**: `Read:eiaf-audit` と `List:eiaf-audit` だけを持つ。`make audit-verify` はこれを使い、アンカーを書ける資格情報を検査に使わない。全版の一覧・取得・保持の設定の取得ができ、put・削除マーカーの作成・版の削除・ほかのバケットの操作は拒否されることを、`make verify PROFILE=file` と `AuditAnchorIT` で確かめた。
- **書込み用の identity はサービスごと**(Issue #43 で解消。P04a ④ のレビューの Major 4)。あるサービスの資格情報で、ほかのサービスのプレフィックスに版を書けないことを、`make verify PROFILE=file`(`s3-audit.sh`)と `AuditAnchorIT` で確かめる。
- **多層防御**: 管理者の資格情報なら、バケットの既定の保持設定の変更と、削除マーカーの作成はできる。どちらも §5・§6 で扱う。
  - 既定の保持設定の変更: アプリは put のたびに COMPLIANCE と保持期限を明示するので、効かない。
  - 削除マーカー: 検証で検出する(`anchor_delete_marker`)。
  - 保存済みの版の保持期限は、誰も短縮できない。

### 8. 残るリスク
- **最後のアンカーより後の記録**は、トリガーを外せるロールなら、次のアンカーまでの間に書き換え・削除して、チェーンをつなぎ直せる。検出の遅れは、アンカーの間隔が上限になる。間隔は各サービスがスケジュールで決める(P05 以降)。
- **保持期限が切れたアンカー**は、管理者が削除できる。削除された後は、その時点までの照合ができない。保持期間は、監査証跡の保存期間以上に設定する(本番は環境ごとの設定)。
- ~~**書込み用の資格情報 `eiaf-audit` は全サービスで共有している**~~(Issue #43 で解消。§7)。以下は P04a ④ の時点の記述。あるサービスの資格情報で、ほかのサービスのプレフィックスにも COMPLIANCE の版を書ける。書いた版は保持期限まで消せないので、そのサービスの検査は保持期限まで `anchor_invalid` か `anchor_hash_mismatch` になりうる。記録そのものは改竄できないため、検出の漏れにはならない。ただし、検査を失敗させ続ける妨害はできる。P04a の時点では、アンカーを書くサービスはまだない。
- **管理者の S3 の資格情報**(`eiaf`)は、audit のバケットのポリシーを外せる。本番では、管理者の資格情報の利用を監査し、S3 の細かい権限(IAM のポリシー)でアプリ用と管理用を分ける。
- ローカルの S3 は平文の http(ADR-0008 の「転送路の暗号化」、#29)。
- **アンカーを保存する前にチェーンを検証していない。** 前回のアンカーより後に改竄されていれば、改竄後の状態をアンカーとして固定する。ただし、前回のアンカーより前の改竄は、その後の検査で検出できる。保存の前の検証は、P05 でスケジュールに結線するときに、処理の時間とあわせて決める。
- **アンカーが古くなったことを検出しない。** アンカーがない場合は「注意」を出すが、終了コードは 0 のまま。スケジュールの結線漏れや、アンカーの保存の失敗が続く状況は、P05 で最後に成功した時刻のメトリクスとアラートを設けて扱う。
- **監査のメトリクス**(追記の失敗・所要時間・ロックの待ち、アンカーの保存の成否): P05 で、`platform/observability` を経由して出す。
- **traceparent と Correlation ID** は、呼び出し側が `AuditEvent` に渡す。現在のコンテキストから埋めるヘルパは、P05 でサービスに結線するときに用意する。

## Alternatives Considered
- **`SELECT ... FOR UPDATE` でチェーンの先頭の行をロックする**: 先頭を持つ表が 1 つ増え、その行の更新(UPDATE)が必要になり、追記専用の表の権限の設計と合わない。advisory lock なら表を増やさずに済む。不採用。
- **SERIALIZABLE で直列にする**: 同時に追記すると直列化の失敗(40001)になり、呼び出し側にリトライが要る。業務のトランザクションの分離レベルも変わってしまう。不採用。
- **ハッシュを DB の関数(トリガー)で計算する**: アプリが直列化に関わらずに済むが、検証の側(Kotlin)と同じ直列化を PL/pgSQL でも実装して、一致を保つ必要がある。トリガーを外すと計算もされなくなる。不採用。
- **JSON の正規化(RFC 8785 JCS)で直列化する**: 仕様が大きく(数値の表現・Unicode の扱い)、NULL と欠落の区別を別に決める必要がある。長さを前に置く固定の順序の形式のほうが、仕様の表が短く、実装も独立に書ける(テストの期待値を Python で作れた)。不採用。
- **記録ごとに独立したハッシュを取る(チェーンにしない)**: 1 件の書き換えは検出できるが、削除・差し込み・順序の入れ替えを検出できない。不採用。
- **AWS SDK for Kotlin**: OkHttp・CRT を引き込み、Kotlin の版とも結びつく。不採用。
- **SigV4 の署名を自前で実装する**: 依存は減るが、署名はセキュリティに関わる部分で、誤りが改竄不能性の実証を損なう。不採用。
- **バケットポリシーの Principal を `"*"` にする**: audit の管理操作は拒否できるが、管理者も同じく拒否され、ポリシーそのものを消せなくなった(ローカルでは `make clean` まで戻せない)。不採用。
- **SeaweedFS の `Write` を使わず、別の S3 互換ストレージにする**: ADR-0015 の比較の結果を変えるほどの理由ではない(ポリシーで塞げた)。不採用。

## Consequences(トレードオフ)
- 1 つのサービスの DB での追記は、直列にした分だけ上限がある(ローカルの目安で毎秒 2,000 件前後)。これを超える量を記録する連携では、記録の粒度(1 件の業務トランザクションに 1 件)を見直すか、チェーンを分ける設計(ADR の改訂)が要る。
- アプリ用のロールを追加したため、既存のローカル基盤では `make clean` が 1 回必要になる。
- `file` / `b2b` profile に、初期化のコンテナ(`seaweedfs-init`)が増えた。初期化を終えた後も待機し(メモリの上限 256 MiB)、ヘルスチェックで完了を示す。終了させると、CI の Compose v2.38.2 の `up --wait` が、終了コード 0 でも失敗として扱う(PR #44 の CI で確認)。待機させることで、`make up` は初期化の完了まで待つ。
- 監査テーブルのマイグレーションは、各サービスのマイグレーションとは別の履歴で管理される。サービスは起動時に `AuditSchema.migrate` も呼ぶ(P05 以降)。
- SeaweedFS を更新するときは、バケットポリシーの Principal の解釈と Write の範囲が変わっていないかを、`make verify PROFILE=file` で確かめる。

## 改訂履歴
- 2026-10-01: P05 ⑧ で、アンカーの書込み用の identity をサービスごと(`eiaf-audit-{service}`。`Write:eiaf-audit/anchors/{service}/*`)にし、共有の `eiaf-audit` を削除した(§7・§8。Issue #43)。`S3AnchorStoreConfig` の資格情報の名前の既定をやめ、`make audit-verify` は `AUDIT_VERIFY_S3_*` の名前で読む。
