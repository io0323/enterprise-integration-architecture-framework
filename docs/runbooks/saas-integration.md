# Runbook: SaaS(CRM)の顧客の連携

## 対象
- CRM(SaaS。ローカルは saas-mock)の顧客を、Canonical Model の `Customer` にして `sales.customer.changed.v1`(INT-SALES-007)に出す連携(P10。ADR-0035・ADR-0036)。
  - **webhook-receiver**(INT-SALES-005): Gateway の `/sales/v1/webhooks/crm` で署名した Webhook を受け、受け取りの記録と取り込みのトピック
    `integration.crm-webhook.received.v1`(INT-INTEGRATION-001。保持 7 日)への Outbox を書いて 204 を返す。発行はコネクタ `webhook-receiver-outbox`。
  - **integration-flow**: 取り込みのトピックを読み(Consumer Group `integration-flow.webhook-ingest`)、Flow の定義(`contracts/flows`)で写し、
    新しい版だけを Outbox に書く。CRM の API(INT-SALES-006)を定期に同期して、Webhook の取りこぼしを補う。発行はコネクタ `integration-flow-outbox`。
  - 新旧は顧客の版(`version`)で判定する。Webhook と定期の同期は同じ記録(`integration_flow` の DB の `flow_version`)を使う。
- ローカルの起動: `make up PROFILE=saas`。
- 鍵の差し替えは `docs/runbooks/webhook-secret-rotation.md`。

| アラート | 重大度 | 見る節 |
|---|---|---|
| `WebhookSignatureRejected` | warning | [Webhook の署名を拒否し続けているとき](#webhook-の署名を拒否し続けているとき) |
| `WebhookReceiveFailed` | warning | [Webhook を記録できないとき](#webhook-を記録できないとき) |
| `FlowRecordsFailed` | warning | [Flow で処理できないレコードがあるとき](#flow-で処理できないレコードがあるとき) |
| `FlowSyncNotSucceeding` / `SaasCrmCircuitOpen` | warning | [定期の同期が成功しないとき](#定期の同期が成功しないとき) |
| `CdcConnectorDown`(`slot_name="webhook_receiver_outbox"` / `"integration_flow_outbox"`) | critical | `docs/runbooks/cdc-outbox-lag.md` |
| `EventConsumerLagHigh` などの Consumer のアラート(`consumer_group="integration-flow.webhook-ingest"`) | warning | `docs/runbooks/event-dlq-replay.md#consumer-が進まないとき` |

## まず見るもの
- ダッシュボード **SaaS — CRM customers**(Grafana のフォルダ EIAF): 受けた要求と拒否の理由・一致した鍵・Flow の結果・同期・lag・CRM の API の
  リトライと Circuit Breaker。
- ログ: `make logs SERVICE=webhook-receiver` / `SERVICE=integration-flow` / `SERVICE=saas-mock`。本文・顧客の値は出ない(イベント ID・顧客 ID・理由のコードだけ)。
- 受け取りの記録(`webhook_receiver` の DB。本文は持たない):
  ```sql
  SELECT webhook_id, source, signed_at, received_at FROM webhook_receipt ORDER BY received_at DESC LIMIT 20;
  ```
- 新旧の判定の記録と Watermark(`integration_flow` の DB):
  ```sql
  SELECT flow_id, record_key, version, updated_at FROM flow_version ORDER BY updated_at DESC LIMIT 20;
  SELECT flow_id, connector, watermark, updated_at FROM sync_watermark;
  ```
- saas-mock の送った Webhook の状態(ローカル): `curl -s -H "Authorization: Bearer $SAAS_MOCK_ADMIN_TOKEN" http://localhost:19280/admin/webhooks`
  (トークンは `.env` から環境変数に読み込んで使う。値を画面やチケットに貼らない)。

## Webhook の署名を拒否し続けているとき
`WebhookSignatureRejected`(15 分に 5 件を超える署名の不一致・時刻の範囲外)。

- `reason="webhook_signature_invalid"`: 鍵の不一致。直前に鍵を差し替えたなら `docs/runbooks/webhook-secret-rotation.md#差し替えの後に署名が拒否されるとき`。
  差し替えていないのに続くなら、なりすましの試みを疑う。Gateway のアクセスログで接続元を確かめ、必要なら Gateway で遮断する。
- `reason="webhook_timestamp_out_of_range"`: 署名の時刻が前後 5 分を外れた(リプレイ)。
  - 受け口(コンテナ)の時計がずれていないかを確かめる。
  - 送り手が再送のときに署名し直していない(元の時刻のまま再送している)なら、送り手の仕様の問題。許容の幅を広げずに、相手ごとに
    ADR-0035 を改訂して決める(ADR-0035 §2)。
- 拒否した Webhook は記録していない。原因を直した後は、送り手の再送か定期の同期で取り込まれる。

## Webhook を記録できないとき
`WebhookReceiveFailed`(受け口が送り手に 503 を返している)。

- `reason="webhook_secret_unavailable"`: 鍵のファイル(`receiver-current`)を読めない。ファイルがあるか・空でないか・コンテナの利用者が読めるか(0600 でホストの利用者)を確かめる。
- `reason="unavailable"`: DB に書けない。`make logs SERVICE=webhook-receiver` と PostgreSQL の状態を確かめる。
- 記録していないので、送り手の再送で取りこぼしなく取り込まれる(同じ webhook-id の重複にもならない)。再送を尽くした Webhook は
  定期の同期(15 分ごと)が補う。急ぐときは saas-mock の管理の API(`POST /admin/webhooks/{id}/redeliver`)で送り直す。

## Flow で処理できないレコードがあるとき
`FlowRecordsFailed`。

- `path="webhook"`: 写せない(本文が JSON でない・Flow の定義の変換に合わない)・Canonical の検証を通らない(例 メールの形式)Webhook を、
  `integration.crm-webhook.received.v1.dlq` に隔離した。DLQ の `eiaf.dlq.reason`・`eiaf.dlq.detail`(項目の場所だけ。値は入れない)を見る:
  `docs/runbooks/event-dlq-replay.md#dlq-に入ったとき`。
  - Flow の定義の誤りなら、`contracts/flows` を直して PR(contract-check と `make` の validate)を通し、integration-flow を入れ替えてから Replay する:
    `make dlq-replay ARGS="--topic integration.crm-webhook.received.v1.dlq --limit 10"`(既定は dry-run。確かめてから `--execute`)。
    Replay は `ce_id` を変えないので、冪等消費の記録(processed_message)で二重には処理しない。
  - SaaS のデータの誤り(例 メールの形式)なら、SaaS の側で直してもらう。直した変更は新しい版の Webhook で届く(DLQ のものは古い版なので戻さなくてよい)。
- `path="poll"`: 定期の同期で写せないレコードがあった。その回は Watermark を進めないので、次の回にもう一度読む(出し直しはしない)。
  ログの `Flow crm-customer を同期しました(SyncReport(... failed=N))` で数を確かめ、上と同じく Flow の定義か SaaS のデータを直す。

## 定期の同期が成功しないとき
`FlowSyncNotSucceeding`(30 分の間成功していない)・`SaasCrmCircuitOpen`。

- ログの `Flow crm-customer を同期できません(<code>)` の code を見る。
  - 429 が続く: CRM の Rate Limit。Retry-After を待ってリトライしている(`eia_resilience_retries_total{eia_dependency_name="saas-crm"}`)。
    Rate Limit の枠を増やせないなら、Flow の `every` を延ばす(`contracts/flows` の PR)。
  - 401: API トークン(`INTEGRATION_FLOW_CRM_API_TOKEN`)の誤り・失効。SaaS の管理画面で発行し直し、秘密情報を差し替える。
  - 接続できない・5xx: SaaS の障害。Circuit Breaker が開いている間は送らない。SaaS の復旧を待つ(Webhook の経路は別に動いている)。
- 同期の失敗は Watermark を進めないので、復旧の後の回で、止まっていた間の変更をまとめて読む(取りこぼさない)。

## 取り込み直すとき(全件の同期・特定の顧客の出し直し)
- **全件をもう一度同期する**(例 CRM の側の大量の修正の後): Watermark を消す。次の回は全件を読み、記録より新しい版だけを出す。
  ```sql
  -- integration_flow の DB に所有者(integration_flow)で接続して実行する(アプリのロールは消せない)
  DELETE FROM sync_watermark WHERE flow_id = 'crm-customer';
  ```
- **特定の顧客を出し直す**(例 消費者の側でデータを失った): その顧客の新旧の記録を消す。次に届く Webhook か同期で、同じ版でも出す。
  ```sql
  DELETE FROM flow_version WHERE flow_id = 'crm-customer' AND record_key = '<顧客 ID>';
  ```
  全件を出し直すときは、Flow の記録をすべて消してから Watermark も消す。消費者は同じ顧客の同じ版を受け取りうる(At-Least-Once。消費者の冪等が前提)。

## 参考
- ADR-0035(Webhook の受信と SaaS の Connector)、ADR-0036(Flow の定義)、ADR-0007(Outbox)、ADR-0028(DLQ・冪等消費)
- `docs/runbooks/webhook-secret-rotation.md`、`docs/runbooks/event-dlq-replay.md`、`docs/runbooks/cdc-outbox-lag.md`
