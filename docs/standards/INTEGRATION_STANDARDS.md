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

### File manifest(`contracts/files/manifest.v1.schema.json`)
必須項目: `file`, `recordCount`, `sha256`, `schemaVersion`, `createdAt`(UTC), `traceparent`, `correlationId`。

## 3. HTTP ステータスとリトライ
- Retry 対象: 408, 429, 502, 503, 504, 接続エラー。429/503 は `Retry-After` を優先。
- 既定 RetryPolicy: initial 500ms, multiplier 2.0, max 3 attempts(初回を含む。リトライは 2 回), cap 30s, full jitter(ADR-0011)。
- Retry-After が上限(cap)を超える場合は待たずに打ち切る(ADR-0011)。

## 4. イベント互換性
- Schema Registry 互換モード: BACKWARD(トピック単位)。
- 追加フィールドは default 必須。削除・型変更・リネームは新バージョントピック。

## 5. Integration Catalog YAML
```yaml
id: INT-SALES-001
name: Order Created Event
style: event            # rest | graphql | grpc | webhook | mqtt | event | batch | etl | cdc | file | edi | ipaas
pattern: pub-sub
provider: { owner: team-order, system: order-service }
consumers:
  - { owner: team-inventory, system: inventory-service }
contract: contracts/asyncapi/order-events.v1.yaml
tier: 1                 # 1 | 2 | 3
dataClassification: internal   # public | internal | confidential | restricted
slo: { availability: "99.99", latencyP99: "5s" }
lifecycle: active       # proposed | design | active | deprecated | retired
```
