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

## 2. 標準ヘッダ
| ヘッダ | HTTP | Kafka / MQTT v5 | 説明 |
|---|---|---|---|
| traceparent | ○ | ○ | W3C Trace Context |
| X-Correlation-Id / correlationid | ○ | ○ | 業務トランザクション ID(入口で採番) |
| Idempotency-Key | POST 必須 | — | 24h 保持 |
| ce_id, ce_source, ce_type, ce_time, ce_specversion | — | ○ | CloudEvents binary mode |

### Idempotency-Key の応答(ADR-0022 §3)
- 部品は `platform/api` の `respondIdempotently`(判定は `IdempotencyHandler`、保存先は `IdempotencyStore` の Port)。キーの範囲はクライアント(`azp`)ごと。
- 同じキーの再送: 内容(指紋 = メソッド・パス・正規化した本文)が同じなら保存した応答を返し `Idempotent-Replayed: true` を付ける。違えば 422。処理中なら 409 と `Retry-After`。キーがない・不正なら 400。
- 5xx・429・408 は保存せず、業務の更新も取り消す(同じキーで再試行できる)。これが成り立つのは、副作用がすべて同じトランザクションの中にある場合だけ。**イベントは Outbox(ADR-0007)を通して発行する。**

### File manifest(`contracts/files/manifest.v1.schema.json`)
必須項目: `file`, `recordCount`, `sha256`, `schemaVersion`, `createdAt`(UTC), `traceparent`, `correlationId`。

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
