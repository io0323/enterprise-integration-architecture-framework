# P14: Tier 別の NFR の計測

ROADMAP P14 の「Tier 別 NFR(Framework 18 章)の計測レポート」。計測の方法と判断は ADR-0048。

> 計測中。数値は GitHub Actions の `nfr` ワークフローの成果物から書き写す(P14 ⑩b)。

## 計測の動かし方
- **主な値**: GitHub Actions の `nfr` ワークフロー(`.github/workflows/nfr.yml`)を手動で動かす(Actions のタブ → nfr → Run workflow)。
  A(`order saga bff`)・B(`cdc iot saas`)・C(`order saga bff`、`E2E=1`)を別のジョブで並列に測り、結果の JSON と環境の記録(CPU の機種を含む)を成果物 `nfr-a`・`nfr-b`・`nfr-c` に残す。
  入力は、測る段(`stages`)・各シナリオの回数(`runs`、既定 2)・起動した後の load average の上限(`max_load`)。
- **手元(補助)**: `make nfr-up PROFILE="order saga bff"` で起動し、`make nfr-measure PROFILE="order saga bff" SCENARIO=order-api` で測る。
  シナリオは `order-api`・`storefront`(A)、`webhook`・`iot`・`cdc`(B。`PROFILE="cdc iot saas"`)、`caller-deadline`(C。`E2E=1`)。
  手元の値は「ローカルの参照実装での目安」で、本番の性能値ではない。
- 計測の前後の確認・Gateway の枠の扱い・遅延の測り方は ADR-0048 §2〜§4。計測の間に Kafka が切り離された回は、結果の JSON に `pipelineCheck.usable: false` が付き、使わない。

## 連携ごとの結果
表は `python3 scripts/reports/nfr-table.py --write` がカタログ(`contracts/catalog`)と計測の結果(`docs/reports/p14-nfr-integrations.json`)から作る。
カタログのすべての連携が載っていることは `./gradlew build`(`NfrReportSpec`)が確かめる(数値は確かめない)。

<!-- nfr-table:begin -->
| ID | 名前 | 方式 | Tier | カタログの SLO(可用性 / p99) | Framework の Tier の目標 | 計測 | 結果 | 判定 | 計測しない理由・備考 |
|---|---|---|---|---|---|---|---|---|---|
| INT-B2B-001 | Partner Purchase Orders (EDIFACT ORDERS) | edi | 2 | 99.9% / 15m | 99.9% / 時間枠(7・9 章) | 計測しない | TBD | — | TBD |
| INT-B2B-002 | Syntax Acknowledgements (EDIFACT CONTRL) | edi | 2 | 99.9% / 15m | 99.9% / 時間枠(7・9 章) | 計測しない | TBD | — | TBD |
| INT-B2B-003 | Business Acknowledgements (EDIFACT APERAK) | edi | 2 | 99.9% / 1h | 99.9% / 時間枠(7・9 章) | 計測しない | TBD | — | TBD |
| INT-INTEGRATION-001 | CRM Webhook Ingest | event | 2 | 99.9% / 5s | 99.9% / Event < 30s | 計測しない | TBD | — | TBD |
| INT-INVENTORY-001 | Inventory Stock Commands | event | 1 | 99.9% / 5s | 99.99% / Event E2E < 5s | 計測しない | TBD | — | TBD |
| INT-INVENTORY-002 | Inventory Stock Events | event | 1 | 99.9% / 5s | 99.99% / Event E2E < 5s | 計測しない | TBD | — | TBD |
| INT-INVENTORY-003 | Stock Query | grpc | 2 | 99.9% / 200ms | 99.9% / API p99 < 1s | 計測しない | TBD | — | TBD |
| INT-IOT-001 | Device Telemetry (MQTT) | mqtt | 2 | 99.9% / 5s | 99.9% / Event < 30s | 計測しない | TBD | — | TBD |
| INT-IOT-002 | IoT Telemetry Events | event | 2 | 99.9% / 5s | 99.9% / Event < 30s | 計測しない | TBD | — | TBD |
| INT-PAYMENT-001 | Payment Commands | event | 1 | 99.9% / 5s | 99.99% / Event E2E < 5s | 計測しない | TBD | — | TBD |
| INT-PAYMENT-002 | Payment Events | event | 1 | 99.9% / 5s | 99.99% / Event E2E < 5s | 計測しない | TBD | — | TBD |
| INT-SALES-001 | Order API | rest | 1 | 99.95% / 500ms | 99.99% / API p99 < 300ms | 計測しない | TBD | — | TBD |
| INT-SALES-002 | Order Events | event | 1 | 99.99% / 5s | 99.99% / Event E2E < 5s | 計測しない | TBD | — | TBD |
| INT-SALES-003 | Legacy Order Changes | cdc | 2 | 99.9% / 60s | 99.9% / Event < 30s | 計測しない | TBD | — | TBD |
| INT-SALES-004 | Daily Sales File | file | 2 | 99.9% / 8h | 99.9% / 時間枠(7・9 章) | 計測しない | TBD | — | TBD |
| INT-SALES-005 | CRM Webhook | webhook | 2 | 99.9% / 1s | 99.9% / API p99 < 1s | 計測しない | TBD | — | TBD |
| INT-SALES-006 | CRM Customer API | rest | 3 | 99.5% / 2s | 99.5% / バッチの SLA | 計測しない | TBD | — | TBD |
| INT-SALES-007 | Customer Events | event | 2 | 99.9% / 30s | 99.9% / Event < 30s | 計測しない | TBD | — | TBD |
| INT-SHIPPING-001 | Shipping Commands | event | 1 | 99.9% / 5s | 99.99% / Event E2E < 5s | 計測しない | TBD | — | TBD |
| INT-SHIPPING-002 | Shipping Events | event | 1 | 99.9% / 5s | 99.99% / Event E2E < 5s | 計測しない | TBD | — | TBD |
| INT-SHIPPING-003 | Shipment Query | grpc | 2 | 99.9% / 200ms | 99.9% / API p99 < 1s | 計測しない | TBD | — | TBD |
| INT-STOREFRONT-001 | Storefront GraphQL | graphql | 2 | 99.9% / 1s | 99.9% / API p99 < 1s | 計測しない | TBD | — | TBD |
<!-- nfr-table:end -->
