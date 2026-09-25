# Integration Standards(Framework の実装規約)

## 1. 命名
| 対象 | 規約 | 例 |
|---|---|---|
| 連携ID | `INT-{DOMAIN}-{NNN}` | INT-SALES-001 |
| REST | `/v{n}/{resource}`(複数形・kebab-case) | /v1/orders |
| Topic | `{domain}.{entity}.{event}.v{n}` | sales.order.created.v1 |
| DLQ | `{topic}.dlq` | sales.order.created.v1.dlq |
| Consumer Group | `{service}.{purpose}` | inventory.reservation |
| Avro | namespace `{basePackage}.events.{domain}`、record は PascalCase | OrderCreated |
| File | `{system}_{dataset}_{yyyyMMddHHmmss}_{seq}.{ext}` + `.manifest.json` | sales_daily_20260925010000_001.parquet |
| MQTT | `devices/{tenant}/{deviceId}/{channel}` | devices/t1/d-001/telemetry |

## 2. 標準ヘッダ
| ヘッダ | HTTP | Kafka / MQTT v5 | 説明 |
|---|---|---|---|
| traceparent | ○ | ○ | W3C Trace Context |
| X-Correlation-Id / correlationid | ○ | ○ | 業務トランザクション ID(入口で採番) |
| Idempotency-Key | POST 必須 | — | 24h 保持 |
| ce_id, ce_source, ce_type, ce_time, ce_specversion | — | ○ | CloudEvents binary mode |

## 3. HTTP ステータスとリトライ
- Retry 対象: 408, 429, 502, 503, 504, 接続エラー。429/503 は `Retry-After` を優先。
- 既定 RetryPolicy: initial 500ms, multiplier 2.0, max 3 attempts, cap 30s, full jitter。

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
