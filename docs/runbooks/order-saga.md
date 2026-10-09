# Runbook: 注文 Saga(order-service の Orchestrator)

## 対象
注文の受付の後の在庫の引当 → 決済の承認 → 出荷を進める注文 Saga(order-service が Orchestrator。ADR-0029)。
状態遷移は `docs/architecture/order-saga.md`。返信の受信(Consumer Group `order.saga`)と DLQ は `docs/runbooks/event-dlq-replay.md`(ADR-0028)。

| アラート | 重大度 | 見る節 |
|---|---|---|
| `OrderSagaCompensationStalled` | critical | [補償が終わらないとき](#補償が終わらないとき) |
| `EventDeadLetters`(`topic` が返信の DLQ) | warning | [返信が DLQ に入ったとき](#返信が-dlq-に入ったとき) |
| `EventConsumerStalled` / `EventConsumerUnavailable`(`order.saga`) | warning | [返信を読めないとき](#返信を読めないとき) |

## まず見るもの
- Saga の状態(order の DB。読み取りだけ。値は個人情報を含まない):
  ```sql
  -- 終わっていない Saga を、期限の古い順に
  SELECT saga_id, order_id, state, failure, resends, deadline_at, clock_timestamp() - updated_at AS since
    FROM order_saga WHERE deadline_at IS NOT NULL ORDER BY deadline_at LIMIT 50;
  ```
  ローカルでは `docker compose -f infra/local/docker-compose.yml --env-file infra/local/images.env --env-file infra/local/.env exec postgres psql -U postgres -d order_service`。
- メトリクス(Prometheus / Grafana の Explore): `eia_saga_transitions_total{from,to,failure}`・`eia_saga_resends_total{state}`・`eia_saga_stalled_total{state}`・`eia_saga_ignored_total{state,signal}`、返信の受信の `eia_consumer_*{messaging_consumer_group_name="order.saga"}`。
- ログ(`make logs SERVICE=order-service`)。Saga ID で検索する(値は出ない)。

## 補償が終わらないとき
`OrderSagaCompensationStalled`。補償のコマンド(`state` の段のもの)を、上限の回数(既定 5 回 = 補償の送り直しの間隔 30 秒 × 5)を超えて送り直している。
**在庫の引当・決済の与信を押さえたまま** なので、早く原因を除く。送り直しは止まらずに続く(補償は必ず終える。ADR-0029 §3)。参加者が回復すれば、冪等のため同じ結果が返り、Saga は進む。**手で `order_saga` を書き換えない**(参加者の記録と食い違う)。

`state` で参加者を決める:

| `state` | 送っているコマンド | 参加者 | 待っている返信 |
|---|---|---|---|
| `RELEASING_STOCK` | `inventory.stock.cmd-release.v1` | inventory-service | `inventory.stock.released.v1` |
| `VOIDING_PAYMENT` | `payment.payment.cmd-void.v1` | payment-service | `payment.payment.voided.v1` |
| `CANCELLING_SHIPMENT` | `shipping.shipment.cmd-cancel.v1` | shipping-service | `shipping.shipment.cancelled.v1` |

1. コマンドが出ているか: order の Outbox のコネクタ `order-outbox` が RUNNING か(`docs/runbooks/cdc-outbox-lag.md`)。止まっていれば、コマンドは Kafka に届いていない。
2. 参加者が処理しているか: 参加者のコマンドの Consumer Group(`inventory.command` など)の lag と `EventConsumerStalled` / `EventConsumerUnavailable`。参加者の `/health/ready`。
3. コマンドが参加者の DLQ に入っていないか(`{コマンドのトピック}.dlq`)。入っていれば `docs/runbooks/event-dlq-replay.md` で原因を除き、Replay する(送り直しで同じコマンドが届いていれば、Replay は要らないことが多い)。
4. 返信が出ているか: 参加者の Outbox のコネクタ(`inventory-outbox` など)が RUNNING か。
5. 返信を order が処理しているか: 返信の DLQ([返信が DLQ に入ったとき](#返信が-dlq-に入ったとき))と、[返信を読めないとき](#返信を読めないとき)。

ローカルで order の profile だけを動かしているときは参加者がいないので、このアラートは正常な動き(`infra/local/README.md`)。

## 返信が DLQ に入ったとき
返信のトピック(`inventory.stock.reserved.v1` など)の DLQ。`eiaf.dlq.reason` で決める(手順は `docs/runbooks/event-dlq-replay.md`):

| reason | 意味 | 対応 |
|---|---|---|
| `NOT_FOUND` | 知らない Saga ID の返信 | 別の環境の返信・手で送ったメッセージなど。order の DB に Saga がなければ **戻さない** |
| `UNKNOWN_REPLY_VALUE` | `reason` / `outcome` が読み手の知らない値(参加者の契約の新しい版の値) | 契約の互換性の進め方(ADR-0014)の誤り。order を新しい版の値に対応させてから戻す。それまで Saga は期限切れで補償に進む |
| `INVALID_HEADERS`・`UNDECODABLE` | 参加者の発行の誤り | 参加者を直す。戻さない |

Saga は返信がなくても、期限切れで補償に進む(前進の段)か、補償のコマンドを送り直す(補償の段)。返信を戻すのは、返信が正しく、Saga がまだその返信を待っているときだけにする。期限切れの後に遅れて戻した返信は無視される(`eia_saga_ignored_total`)。

## 返信を読めないとき
`order.saga` の `EventConsumerUnavailable`(order の DB または Schema Registry が使えない)・`EventConsumerStalled`。
- 返信の読み取りが止まっても、**order-service の `/health/ready` は 503 にならない**(API は注文を受け付け続ける。ADR-0029 改訂履歴)。アラートとメトリクスで判断する。
- 読み直しは自動で続く。原因(DB・Apicurio)を除けば、止まった位置から処理する。止まっている間に期限を過ぎた Saga は補償に進むので、回復の後に届いた前進の返信は無視される(在庫・与信は補償で戻る)。
