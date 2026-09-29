# ADR-0015: S3 互換ストレージに SeaweedFS を使う(MinIO の置き換え)
- Status: Accepted
- Date: 2026-09-26
- Framework 参照章: 1.9, 9.2, 11, 14.1, 17
- 関連: ADR-0003(MinIO の部分を置き換える)、ADR-0008(Audit の日次アンカーの保存先を置き換える)

## Context
- ADR-0003 はローカルの S3 互換ストレージに MinIO を選び、ADR-0008 は Audit の日次アンカーを MinIO(Object Lock / Compliance モード)に保存するとしている。
- P03 の着手時(2026-09-26)に確認したところ、MinIO はそのままでは使えなくなっていた。
  - GitHub の `minio/minio` はアーカイブ済み(`archived: true`。最後の push は 2026-04-24)。セキュリティ修正も含めて更新されない。
  - Docker Hub の `minio/minio` リポジトリは存在せず(`object not found`)、`quay.io/minio/minio` は匿名では取得できない。公開のコンテナイメージを版とダイジェストで固定する、という ADR-0016 の方針を満たせない。
- 本リポジトリでアプリが使う S3 の機能は次のとおり。
  - File(P09): put / get、一覧、一時名から正式名への copy(Framework 9.2 の完了通知)、大きなファイルのマルチパートアップロード、受け渡し用の署名付き URL
  - Audit(P04a、ADR-0008): バージョニングと Object Lock(Compliance モード)の保持期限
  - B2B(P12): 原本の保管(Object Lock)

## Decision
### 1. SeaweedFS を使う
- `chrislusf/seaweedfs`(Apache-2.0)を使う。master・volume・filer・S3 ゲートウェイを 1 プロセス(`weed server -s3`)で動かし、`file` と `b2b` の profile に含める(ADR-0016)。
- アプリからは S3 API だけを使い、SeaweedFS に固有の API(filer の HTTP API など)は使わない(ADR-0003 の「標準 I/F で利用する」)。
- 版は ADR-0016 に従って `infra/local/images.env` にタグとダイジェストで固定する。

### 2. path-style でアクセスすることを前提にする
- バケットは URL のパス(`http://localhost:19333/{bucket}/{key}`)で指定する。virtual-hosted style(`{bucket}.localhost`)は、ローカルでバケットごとの名前解決が必要になり、コンテナの内外で名前が変わるため使わない。
- アプリの S3 クライアントは addressing style を設定で切り替えられるようにし、ローカルの既定を path-style にする(AWS SDK for Kotlin / Java では `forcePathStyle = true`)。本番のストレージでは製品に合わせて設定する。
- 署名付き URL の署名にはホスト名が含まれる。**URL を使う側から見えるエンドポイント**で署名する(ホストのアプリなら `http://localhost:19333`、コンテナ内なら `http://seaweedfs:8333`)。

### 3. 互換性を検査で担保する
`make verify PROFILE=file`(`infra/local/scripts/s3-compat.sh`。AWS CLI 2.37.4 を path-style で使う)で、アプリが使う機能を毎回検査する。2026-09-26 の結果(SeaweedFS 4.47)は、次のすべてが成功した。

| 区分 | 検査 |
|---|---|
| 基本 | バケット作成 / put-object / get-object(内容の一致)/ list-objects-v2(prefix)/ copy-object |
| マルチパート | 5 MiB + 1 MiB の 2 パートで完了し内容が一致する / abort できる |
| 署名付き URL | GET で認証情報なしに取得できる / 署名を改ざんした URL は 403 / ホスト向けの URL が path-style になる |
| バージョニング | 有効にでき、同じキーへの 2 回の put で版が分かれ、古い版を version-id で取得できる |
| Object Lock | `--object-lock-enabled-for-bucket` でバケットを作成でき、設定が Enabled でバージョニングも有効になる / COMPLIANCE の保持期限つきで put でき、期限内はその版を削除できない / 期限の経過後に削除できる |

- AWS CLI 2.x は既定で追加のチェックサム(CRC 系)を送るが、SeaweedFS 4.47 はそのまま受け付けた。
- 検査が 1 つでも失敗する版には更新しない。

## Alternatives Considered
| 観点 | **SeaweedFS 4.47(採用)** | RustFS 1.0.0 | MinIO を自前でビルドする |
|---|---|---|---|
| 保守状況 | 活発(4.47 は 2026-09-14) | 活発。ただし 1.0.0(2026-09-16)が出たばかりで、安定版としての実績が短い | 上流がアーカイブ済み。脆弱性の修正を自分たちで取り込む必要がある |
| ライセンス | Apache-2.0 | Apache-2.0 | AGPLv3 |
| 公開イメージ | Docker Hub に amd64 / arm64 のマルチアーキテクチャのイメージがある | Docker Hub にある | なし。Dockerfile とビルド手順を持ち、イメージをどこかに公開する必要がある |
| S3 の互換性 | 上の検査をすべて満たした。Object Lock は `weed/s3api/s3_objectlock` で実装されている | MinIO 互換を掲げ、Object Lock も実装しているとしている | MinIO の互換性がそのまま得られる |
| ローカルの負荷 | 1 コンテナ・実測で約 140 MiB(`docs/reports/p03-local-infrastructure.md`) | 1 コンテナ | 1 コンテナ |
| 不採用の理由 | — | 1.0 直後で、P04a の Audit(改竄不能性の実証)に使うには実績が足りない。1〜2 マイナー版を経ても検査を満たすなら、再評価の候補にする | 保守されないソフトウェアを、改竄不能性を示す Audit の保存先にすることになる。ビルドとイメージの公開の手間も増える |

- ほかに Garage(Object Lock を実装していない)と Ceph RGW(ローカルの負荷が大きい)も考えたが、Audit の要件とローカルの負荷を満たさないため比較から外した。

## Consequences(トレードオフ)
- ADR-0003 のミドルウェア一覧の MinIO を SeaweedFS に、ADR-0008 の Audit の保存先を SeaweedFS(Object Lock / Compliance モード)に読み替える。CLAUDE.md・ROADMAP・MODULE_DESIGN の記述もこれに合わせる。
- S3 API だけを使うため、本番で AWS S3・他の S3 互換ストレージへ替えるときも、アプリはエンドポイント・認証情報・addressing style の設定を変えるだけでよい。
- Object Lock の Compliance モードの厳密さ(ルートの権限でも消せないか、保持期限を短くできないか)は、P04a の Audit の統合テストで改めて確かめる。P03 では、保持期限内の版を通常の資格情報で削除できないことまでを確かめた。
- `weed server` を 1 プロセスで動かすのはローカル専用の構成で、冗長性はない。

## 改訂履歴
- 2026-09-29: Object Lock の Compliance モードの厳密さを、P04a ④ で確かめた(ADR-0017 §7)。**管理者の資格情報でも**、保持期限内の版を削除できず(`--bypass-governance-retention` を付けても)、保持期限を短縮できず、GOVERNANCE に変更できなかった。空でないバケットは削除できない。`make verify PROFILE=file`(`scripts/s3-audit.sh`)と `AuditAnchorIT` で毎回確かめる。あわせて、SeaweedFS の identity の `Write` がバケットの管理操作(Object Lock の設定の変更など)を含むことが分かり、audit の identity はバケットポリシーで拒否した(ADR-0017 §7)。
