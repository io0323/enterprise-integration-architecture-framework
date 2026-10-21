# Integration Standards(Framework の実装規約)

## 1. 命名
| 対象 | 規約 | 例 |
|---|---|---|
| 連携ID | `INT-{DOMAIN}-{NNN}` | INT-SALES-001 |
| REST(Gateway 公開) | `/{domain}/v{n}/{resource}`(複数形・kebab-case)(ADR-0005) | /sales/v1/orders |
| REST(サービス内部) | `/v{n}/{resource}`(Gateway が `/{domain}` を除去して転送) | /v1/orders |
| Topic | `{domain}.{entity}.{event}.v{n}` | sales.order.created.v1 |
| Command Topic | `{domain}.{entity}.cmd-{command}.v{n}`(ADR-0006) | inventory.stock.cmd-reserve.v1 |
| DLQ | `{topic}.dlq` | sales.order.created.v1.dlq |
| Consumer Group | `{service}.{purpose}` | inventory.reservation |
| Avro | namespace `{basePackage}.events.{domain}`、record は PascalCase | OrderCreated |
| File | `{system}_{dataset}_{yyyyMMddHHmmss}_{seq}.{ext}` + 同名の `.manifest.json` | sales_daily_20260925010000_001.parquet / sales_daily_20260925010000_001.manifest.json |
| File 仕様 | `contracts/files/{system}_{dataset}.v{n}.yaml` | contracts/files/sales_daily.v1.yaml |
| MQTT | `devices/{tenant}/{deviceId}/{channel}` | devices/t1/d-001/telemetry |

- 連携 ID の `{DOMAIN}` は業務の領域(Topic の domain・API の `/{domain}` と同じ。CC-NAMING-010)で、データの出どころのシステムではない。出どころ(例: レガシー基幹)はカタログの `provider.system` と `description` に書く(例: レガシーの受注の CDC は INT-SALES-003)。

## 2. 標準ヘッダ
| ヘッダ | HTTP | Kafka / MQTT v5 | 説明 |
|---|---|---|---|
| traceparent | ○ | ○ | W3C Trace Context |
| X-Correlation-Id / correlationid | ○ | ○ | 業務トランザクション ID(入口で採番) |
| Idempotency-Key | POST 必須 | — | 24h 保持(Webhook の受け口は webhook-id で代える) |
| webhook-id, webhook-timestamp, webhook-signature | Webhook の受け口で必須 | — | Standard Webhooks の署名(ADR-0035 §2) |
| ce_id, ce_source, ce_type, ce_time, ce_specversion | — | ○ | CloudEvents binary mode |

### DLQ のヘッダ(Framework 6.5。ADR-0026 §7)
処理できないメッセージを `{topic}.dlq` に隔離するときは、キーと値を受け取ったバイト列のまま送り、元のヘッダに次を加える(`platform/messaging-kafka` の `DeadLetterPublisher`。値は UTF-8 の文字列)。`traceparent` は処理したトレースのものに置き換える。

| ヘッダ | 内容 |
|---|---|
| `eiaf.dlq.reason` | 原因の種類(例 `UNKNOWN_STATUS_CODE`・`UNDECODABLE`)。種類の数は有限にする(メトリクスのラベルにも使う) |
| `eiaf.dlq.detail` | 項目・列の名前と破った規則。**値は入れない**(ペイロードの全文のログの禁止・機密区分) |
| `eiaf.dlq.source.topic` / `eiaf.dlq.source.partition` / `eiaf.dlq.source.offset` | 元のメッセージの位置 |
| `eiaf.dlq.attempts` | 処理を試みた回数(決定的な誤りはリトライしないので 1。一時的な失敗のリトライが尽きた場合は 4 = 初回 + 3 回。ADR-0028 §2) |
| `eiaf.dlq.failed-at` | DLQ に送った時刻(ISO 8601 の UTC) |

Consumer(`EventConsumer`。ADR-0028 §2)が自分で判定する `eiaf.dlq.reason` は `INVALID_HEADERS`(CloudEvents のヘッダの欠落・不正)・`UNDECODABLE`(Avro として読めない)・`UNEXPECTED_TOMBSTONE`(値がない)・`UNEXPECTED_EXCEPTION`(処理が想定しない例外を投げた)。それ以外は処理が返したコードを大文字にしたもの。自分の DB・Schema Registry が使えない間のメッセージは DLQ に送らず、読み直す(lag のアラートで検知する)。

### Replay のヘッダ(ADR-0028 §6)
DLQ から元のトピックに戻すときは、キー・値・`ce_id` を含むヘッダを DLQ のまま使い(受信側の冪等消費で重複を捨てられるように)、`eiaf.dlq.*` を外して次を加える(`DeadLetterReplayer`。`make dlq-replay`。値は UTF-8 の文字列)。

| ヘッダ | 内容 |
|---|---|
| `eiaf.replay.dlq.partition` / `eiaf.replay.dlq.offset` | 戻した元の DLQ の位置 |
| `eiaf.replay.reason` | DLQ に入ったときの原因(`eiaf.dlq.reason`) |
| `eiaf.replay.replayed-at` | 戻した時刻(ISO 8601 の UTC) |

DLQ のトピックは、元のメッセージと同じ機密区分として扱う(値をそのまま運ぶため)。保持期間はトピックの定義(`infra/local/kafka/topics.conf`)に書く。

### Idempotency-Key の応答(ADR-0022 §3)
- 部品は `platform/api` の `respondIdempotently`(判定は `IdempotencyHandler`、保存先は `IdempotencyStore` の Port)。キーの範囲はクライアント(`azp`)ごと。
- 同じキーの再送: 内容(指紋 = メソッド・パス・正規化した本文)が同じなら保存した応答を返し `Idempotent-Replayed: true` を付ける。違えば 422。処理中なら 409 と `Retry-After`。キーがない・不正なら 400。
- 5xx・429・408 は保存せず、業務の更新も取り消す(同じキーで再試行できる)。これが成り立つのは、副作用がすべて同じトランザクションの中にある場合だけ。**イベントは Outbox(ADR-0007)を通して発行する。**

### Webhook の署名(ADR-0035 §2)
- SaaS から受ける Webhook は、Standard Webhooks の形の署名で認証する(JWT ではない)。
  - `webhook-id`: イベント ID(再送でも同じ)。重複の判定のキー。
  - `webhook-timestamp`: 署名した時刻(UNIX 秒)。受け口の時刻から前後 5 分を外れたら拒否する(リプレイ)。正当な再送は新しい時刻で署名し直す。
  - `webhook-signature`: `v1,<base64(HMAC-SHA256(secret, "{webhook-id}.{webhook-timestamp}.{本文のバイト列}"))>` を空白区切りで 1 つ以上。
- 受け口は、本文を解析する前に生のバイト列で署名を検証し、定数時間で比べる。鍵の差し替えの間は新旧 2 つの鍵を受け付ける。
- 受け口の OpenAPI の操作には `x-eia-webhook: true` を付ける(contract-check の `CC-API-004`)。

### File manifest(`contracts/files/manifest.v1.schema.json`)
必須項目: `file`, `recordCount`, `sha256`, `schemaVersion`, `createdAt`(UTC), `traceparent`, `correlationId`。

### ファイルの仕様(`contracts/files/{system}_{dataset}.v{n}.yaml`)
- 形式は `contracts/files/file-spec.v1.schema.json`。主な項目:
  - 機密区分・個人情報を含むか(`containsPersonalData`)
  - 1 ファイルの範囲(`granularity`。日の区切りのタイムゾーンを含む)
  - 出せる形式(`formats`。Parquet・CSV など。CSV は区切り・引用・ヘッダ・改行・null の表し方まで書く)
  - 件数の数え方(`recordCount`)
  - 列(名前・論理型・必須性・enum)
- manifest の `schemaVersion` は `{system}_{dataset}.v{n}` で、この仕様を指す。受ける側は、この仕様で件数・列・値を検査する。
- 互換でない変更(列の削除・型や必須性の変更・並べ替え・enum の値の増減・形式の設定の変更)は、新しい版のファイル(`v{n+1}`)で行う。足してよいのは、末尾の任意の列と新しい形式だけ。
- contract-check の `CC-FILE-002`〜`004`・`CC-COMPAT-003` が検査する。
- ファイルの仕様もカタログ(`style: file`)に登録する。

## 3. HTTP ステータスとリトライ
- Retry 対象: 408, 429, 502, 503, 504, 接続エラー。429/503 は `Retry-After` を優先。
- 既定 RetryPolicy: initial 500ms, multiplier 2.0, max 3 attempts(初回を含む。リトライは 2 回), cap 30s, full jitter(ADR-0011)。
- Retry-After が上限(cap)を超える場合は待たずに打ち切る(ADR-0011)。

## 4. イベント互換性
- 互換性モード: **FULL**(BACKWARD かつ FORWARD。イベント・コマンドのトピック単位)(ADR-0014)。CI(contract-check)は origin/main の版と FULL で比較し、Schema Registry(P03〜)は FULL_TRANSITIVE で全バージョンと比較する。
- 追加・削除できるのは default 付きの項目だけ。default のない項目の追加・削除、型変更(拡張を含む)、リネーム、nullable の変更、enum の値の増減は新バージョントピック(許される変更の一覧は ADR-0014 §3)。
- Canonical Model を運ぶ Avro の物理表現(Money は `minorUnits: long` + `currency`、時刻は `timestamp-micros` でマイクロ秒未満は切り捨て)は ADR-0012 に従う。
- 互換性・命名・カタログは `./gradlew :tools:contract-check:run` で検査する(ルール一覧は tools/contract-check/README.md)。

## 5. Integration Catalog YAML
1 ファイル 1 連携で `contracts/catalog/{id}.yaml` に置く。形式は `contracts/catalog/catalog.schema.json` で定義し、contract-check が検査する(ADR-0013)。
```yaml
id: INT-SALES-002
name: Order Events
style: event            # rest | graphql | grpc | webhook | mqtt | event | batch | etl | cdc | file | edi | ipaas
pattern: pub-sub        # request-reply | pub-sub | queue | scheduled | file-transfer | cdc
provider: { owner: team-order, system: order-service }
consumers:
  - { owner: team-inventory, system: inventory-service, group: inventory.reservation }
contract: contracts/asyncapi/order-events.v1.yaml
channels:               # event / cdc では必須。AsyncAPI の channels[*].address と過不足なく一致させる
  - sales.order.created.v1
tier: 1                 # 1 | 2 | 3
dataClassification: internal   # public | internal | confidential | restricted
slo: { availability: "99.99", latencyP99: "5s" }
audit:                  # 任意。この連携が監査を記録するなら書く(ADR-0017 §1)
  actions: [order.create]          # 記録する操作(audit.audit_log の action)
  dataClassification: confidential # 監査の記録の機密区分(actor_id などは個人データになりうる)
  retention: legal                 # legal(法令に従う。期間は環境ごとの設定)か ISO 8601 の期間(例 P7Y)
lifecycle: active       # proposed | design | active | deprecated | retired
```
- `lifecycle` は 1 つの PR で 1 段階だけ前に進める。エントリを消せるのは retired になった後、契約のファイルを消せるのは使う連携がすべて retired になった後(ADR-0034。contract-check の `CC-LIFECYCLE-*`)。状態ごとに書く項目:
  ```yaml
  lifecycle: deprecated
  deprecation: { sunset: 2027-03-31, notice: "#200", replacement: INT-SALES-005 }  # 移行の期限・消費者への通知・移行先(任意)
  ---
  lifecycle: retired
  retirement: { issue: "#210" }        # 資格情報・ACL・FW の穴を閉じる後片付け
  ---
  onboarding: { kind: existing, reason: 2024 年から稼働している連携を登録する }   # 既存の連携を後から登録するときだけ、active で登録できる
  reactivation: { reason: 移行先の廃止が中止された, approver: "@name" }           # deprecated → active に戻すとき
  ```
  移行の期限を過ぎても deprecated のままの連携は、contract-check の警告(ゾンビ連携)に出る。
- `owner` はチーム単位(`team-{name}`)。個人名は使わない(Framework 16.2)。
- `consumers[*].group` は Kafka の Consumer Group(`{service}.{purpose}`)。event / cdc の consumer では必須。
- コマンド(`pattern: queue`、ADR-0006)では、`provider` をコマンドを受信して処理するサービスとし、`consumers` はその受信サービスの 1 件(`group: {service}.command`)だけにする。送信側は `senders` に書く。
  ```yaml
  id: INT-INVENTORY-001
  style: event
  pattern: queue
  provider: { owner: team-inventory, system: inventory-service }
  senders:
    - { owner: team-order, system: order-service }
  consumers:
    - { owner: team-inventory, system: inventory-service, group: inventory.command }
  channels:
    - inventory.stock.cmd-reserve.v1
  ```
- `contracts/` の OpenAPI / AsyncAPI は、どれかのカタログの `contract` に登録されていなければならない(カタログ未登録の連携の禁止。Framework 16.1)。
- **外部の契約**(SaaS のベンダーなどが持ち主の API の写し)は `contracts/external/` に置き、カタログに `external: { vendor, source }` を書く(ADR-0035 §8)。
  owner は連携に責任を持つ社内のチーム。contract-check は自社の API の規則を当てず、ベンダーの破壊的変更は警告(`CC-EXTERNAL-001`)にする。
- **個人情報を運ぶ連携** は `dataRetention`(ISO 8601 の期間。例 `P7D`)を書き、トピックの retention などの設定と一致させる。
- **iPaaS 風の Flow の定義** は `contracts/flows/{id}.v{n}.flow.yaml`(形式は `flow.v1.schema.json`。ADR-0036)。Flow でできるのは取り込み・Canonical への対応・振り分け・届け先の指定だけ。

## 6. エラー応答(Problem Details)
REST のエラーは RFC 9457 の Problem Details(`application/problem+json`)で返す。部品は `platform/api` の `installProblemDetails` / `respondError` / `respondProblem`(ADR-0022 §2)。

- 項目: `type`・`title`・`status`・`detail`(任意)・`correlationId`・`errors`(422 のみ。`field`・`message`)。`Cache-Control: no-store`。再試行の時期が分かれば `Retry-After`(秒)。
- `title` と `detail` は種類ごとの固定の文。例外のメッセージ・スタックトレース・受け取った値は出さない。原因は `correlationId` を起点にログで追う。
- `type` はクライアントがエラーの種類を判別する識別子で、環境ごとに変えない。変更は破壊的変更になる。基底 URI は `https://eiaf.example/problems/`(採用する組織が最初に 1 回だけ決める。ADR-0022 §2)。

| type(基底 URI の後) | status | 使う場面 |
|---|---|---|
| `validation-failed` | 422 | 値域・業務整合の検証エラー(`errors` に項目と理由) |
| `bad-request` | 400 | 本文を読めない(JSON の解析の失敗など) |
| `idempotency-key-missing` | 400 | `Idempotency-Key` がない・形式が不正 |
| `idempotency-key-reused` | 422 | 同じ `Idempotency-Key` で内容の違う要求 |
| `idempotency-request-in-progress` | 409 | 同じ `Idempotency-Key` の要求を処理中(`Retry-After` 付き) |
| `not-found` | 404 | リソースがない・どのルートにも当たらない |
| `conflict` | 409 | リソースの状態と矛盾する要求 |
| `rate-limited` | 429 | Rate Limit の超過(`Retry-After` 付き) |
| `service-unavailable` | 503 | 依存先が一時的に使えない・呼び出し元の締め切りを超えた(分かれば `Retry-After`) |
| `deadline-exceeded` | 503 | 入口で決めたリクエストの予算を超えた(`Retry-After` 付き)。処理は確定していないので、同じ `Idempotency-Key` で再試行できる(ADR-0024 §3) |
| `internal-error` | 500 | 想定外のエラー |
| `unauthorized` | 401 | トークンがない・不正(理由は返さない。ADR-0019 §5) |
| `forbidden` | 403 | スコープが足りない |
| `about:blank` | 405 / 413 / 415 | 状態コード以上の意味がない応答 |
| `about:blank` | 502 / 504 | ゲートウェイが返す(サービスに接続できない / 上流のタイムアウト。504 は結果が分からない。ADR-0023) |

種類を追加するときは、`ProblemType`・この表・契約(OpenAPI)の説明を同時に更新する。
