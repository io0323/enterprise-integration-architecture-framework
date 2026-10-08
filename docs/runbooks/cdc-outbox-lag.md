# Runbook: Outbox の発行の遅延とレプリケーションスロット(CDC)

## 対象
- `prometheus/rules/cdc.rules.yml` のアラートが firing したとき(Prometheus の `/alerts` か、Grafana のダッシュボード **CDC — Outbox** で見る。Alertmanager は置いていない)。

  | アラート | 重大度 | 見出し |
  |---|---|---|
  | `CdcSlotWalAtRisk` | warning | [警告の段階](#警告の段階) |
  | `CdcSlotLagHigh` | warning | [警告の段階](#警告の段階) |
  | `CdcConnectorDown` | critical | [警告の段階](#警告の段階)(まずコネクタの復旧) |
  | `CdcSlotLost` | critical | [lost になったとき](#lost-になったとき) |
  | `CdcOutboxSlotMissing` | critical | [スロットがない](#スロットがない) |
  | `CdcMonitoringDown` | warning | [監視が見えないとき](#監視が見えないとき) |

- 注文のイベント(`sales.order.created.v1`)が届かない、遅れると報告されたとき。

仕組み: order-service は注文と同じトランザクションで Outbox(`outbox.outbox`)に書き、行はすぐに消える。Debezium(Kafka Connect のコネクタ `order-outbox`)が、レプリケーションスロット `order_outbox` で WAL を読んで発行する(ADR-0007)。**発行が止まっている間は、スロットが WAL を保持するので欠けない。** ただし、保持できる WAL には上限(`max_slot_wal_keep_size`。ローカルは 1GB)があり、超えるとスロットは無効になって、その間のイベントは戻らない。

## まず見るもの
```bash
make ps                                                        # kafka-connect・postgres・kafka が healthy か
curl -s localhost:19083/connectors/order-outbox/status         # コネクタとタスクの状態(RUNNING か。FAILED なら trace に原因)
docker compose -f infra/local/docker-compose.yml --env-file infra/local/images.env --env-file infra/local/.env \
  exec -T postgres psql -U postgres -c \
  "select slot_name, active, wal_status, pg_size_pretty(safe_wal_size) safe, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) retained from pg_replication_slots"
make logs SERVICE=kafka-connect                                # Debezium のエラー
```
- `wal_status`: `reserved`(正常)→ `extended`(上限の内側だが、`wal_keep_size` を超えて保持)→ `unreserved`(上限を超えた。次のチェックポイントで消えうる)→ `lost`(無効)。
- `safe_wal_size`: 無効になるまでに書ける WAL の量。`CdcSlotWalAtRisk` は、これが上限の 25% を切ると出る(閾値は上限に対する割合なので、上限を変えると追従する)。

## 警告の段階
`CdcSlotWalAtRisk` / `CdcSlotLagHigh` / `CdcConnectorDown`。**まだイベントは失われていない。** 目的は、スロットが無効になる前に発行を再開させること。

アラートの `slot_name` でコネクタを見分ける: `order_outbox` はコネクタ `order-outbox`、`legacy_juchu` はレガシーの CDC のコネクタ `legacy-juchu`(ADR-0026)。以下のコマンドのコネクタの名前を読み替える。ローカルで profile を切り替えた(cdc の後に order だけ)ために `legacy_juchu` が使われていない場合は、`infra/local/README.md` のトラブルシューティングを見る。

1. **コネクタの復旧を急ぐ**(多くの場合はこれで解消する)
   - タスクが `FAILED`: 原因(status の `trace`、`make logs SERVICE=kafka-connect`)を確かめてから、タスクを再起動する。
     ```bash
     curl -X POST 'localhost:19083/connectors/order-outbox/restart?includeTasks=true&onlyFailed=true'
     ```
   - コネクタが `STOPPED` / `PAUSED`(誰かが止めた): 再開する。`curl -X PUT localhost:19083/connectors/order-outbox/resume`
   - コネクタがない(Connect のコンテナを作り直した・設定の内部トピックが消えた): 登録し直す。`infra/local/scripts/connectors.sh order-outbox`(同じ設定を PUT するだけで、何度実行してもよい。オフセットが残っていれば続きから読む)。
   - Kafka が止まっている: Kafka を先に直す(コネクタは Kafka の復旧を待って続きを送る)。
   - PostgreSQL に接続できない: `make logs SERVICE=postgres`、debezium のロールのパスワード(`.env` の `DEBEZIUM_DB_PASSWORD` と Connect の環境変数)を確かめる。
2. 発行が再開すると、スロットの保持している WAL(Grafana の「保持している WAL」)が下がり、`safe_wal_size` が戻る。アラートは自動で解消する。
3. **すぐに直せず、上限に届きそうなら、一時的に上限を広げる**(スロットを無効にしないための時間稼ぎ)
   - 先に、PostgreSQL のディスクの空きを確かめる。広げた分だけ WAL がディスクを使う。空きが足りなければ広げない(ディスクが溢れると PostgreSQL 全体が止まり、注文 API も止まる)。
     ```bash
     docker compose ... exec -T postgres df -h /var/lib/postgresql
     ```
   - **本番(設定ファイルで上限を決めている場合)**: 再起動なしで変えられる(`max_slot_wal_keep_size` は reload で反映される)。
     ```sql
     ALTER SYSTEM SET max_slot_wal_keep_size = '2GB';
     SELECT pg_reload_conf();
     SELECT setting, unit, source FROM pg_settings WHERE name = 'max_slot_wal_keep_size';  -- 反映されたか(source)
     ```
     直ったら元に戻す: `ALTER SYSTEM RESET max_slot_wal_keep_size; SELECT pg_reload_conf();`
   - **ローカル基盤**: compose が起動引数(`-c max_slot_wal_keep_size=1GB`)で渡しており、**起動引数は `ALTER SYSTEM`(`postgresql.auto.conf`)より優先されるので、上の手順は効かない**(`source` が `command line` のまま。2026-10-08 に確かめた)。`docker-compose.yml` の値を変えて `docker compose ... up -d postgres` で作り直す。PostgreSQL が数秒止まり、接続しているサービスは再接続する(注文 API は短い間 503 になる)。直ったら値を戻して同じく作り直す。
   - アラートの閾値は上限に対する割合なので、広げると `CdcSlotWalAtRisk` / `CdcSlotLagHigh` も新しい上限で判定し直す(Prometheus は `pg_settings_max_slot_wal_keep_size_bytes` で新しい上限を知る)。
4. 直った後: 止まっていた間の注文のイベントが、すべて届いたことを確かめる(遅れて届くだけで欠けない)。

## lost になったとき
`CdcSlotLost`(`wal_status = lost`)。**スロットは無効で、読まれていなかった WAL は消えている。その間のイベントは発行されない。**

- Outbox は行を消す方式(ADR-0007 §2)なので、スロットとコネクタを作り直してスナップショットを取っても、**失われたイベントは戻らない**。業務の表(`orders`)には注文が残っている。
- 作り直しの手段は Issue #80(まだない)。それまでは、下の手順で発行を再開し、失われた期間を記録して、受信側の担当者に知らせる。

1. **失われた期間を記録する**(Issue #80 の作り直しで使う)
   - 始まり: 最後に届いたイベントの `ce_time`(`sales.order.created.v1` の各パーティションの末尾のレコードのうち、いちばん新しいもの)。
     ```bash
     compose=(docker compose -f infra/local/docker-compose.yml --env-file infra/local/images.env --env-file infra/local/.env)
     kafka=("${compose[@]}" exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka /opt/kafka/bin)
     "${kafka[@]}/kafka-get-offsets.sh" --bootstrap-server kafka:9092 --topic sales.order.created.v1 | while IFS=: read -r topic partition end; do
       (( end > 0 )) || continue
       "${kafka[@]}/kafka-console-consumer.sh" --bootstrap-server kafka:9092 --topic "$topic" --partition "$partition" \
         --offset $((end - 1)) --max-messages 1 --timeout-ms 10000 --property print.headers=true --property print.value=false </dev/null 2>/dev/null |
         grep -o 'ce_time:[^,]*'
     done | sort | tail -1
     ```
   - 終わり: 下の 3 で発行が再開した時刻。
   - 期間の注文の件数: `select count(*), min(ordered_at), max(ordered_at) from orders where ordered_at >= '<始まり>'`
2. **無効なスロットとコネクタのオフセットを消す**(残っていると、コネクタは無効なスロットから読もうとして失敗し続ける)
   ```bash
   curl -X PUT localhost:19083/connectors/order-outbox/stop
   curl -X DELETE localhost:19083/connectors/order-outbox/offsets
   ```
   ```sql
   SELECT pg_drop_replication_slot('order_outbox');
   ```
3. **発行を再開する**: `curl -X PUT localhost:19083/connectors/order-outbox/resume`(スロットは Debezium が作り直す。`snapshot.mode=no_data` なので、これから書かれる Outbox の行だけを発行する)。`CdcOutboxSlotMissing` が出ていれば解消する。
4. 失われた期間の注文について、受信側の担当者(INT-SALES-002 の consumers)に知らせる。作り直しの手段ができたら(Issue #80)、その手順で発行し直す。
5. 原因(なぜ上限まで止まっていたか、なぜ警告の段階で直せなかったか)を振り返り、上限・アラート・この手順を見直す。

## スロットがない
`CdcOutboxSlotMissing`。order-service が動いているのに、スロット `order_outbox` がない。**注文は Outbox に書かれるが、誰も WAL を読んでいないので、発行されない。** スロットがない間に書かれた Outbox の行は、後からスロットを作っても読めない(スロットは作った時点より後の WAL しか読まない)。

- 主な原因:
  - コネクタが一度も起動していない(`make up` でコネクタを登録できなかった、`connectors.sh` を実行していない)。
  - スロットが消された(手で `pg_drop_replication_slot` した、PostgreSQL のボリュームを作り直した)。
1. コネクタの状態を確かめる(`curl -s localhost:19083/connectors/order-outbox/status`)。ない、または FAILED なら、[警告の段階](#警告の段階) の 1 で登録・再起動する。Debezium がスロットを作る。
2. スロットができたこと(`select slot_name from pg_replication_slots`)と、アラートが解消したことを確かめる。
3. スロットがなかった期間に注文があれば、そのイベントは失われている。[lost になったとき](#lost-になったとき) の 1・4 と同じく、期間を記録して知らせる(作り直しは Issue #80)。

## 監視が見えないとき
`CdcMonitoringDown`。postgres-exporter・kafka-exporter・kafka-connect(JMX の `:9404`)のどれかから Prometheus が収集できない。**この間は、上のアラートが出ない(スロットが危なくても気づけない)。**

1. `make ps` で該当のコンテナの状態を確かめ、`make logs SERVICE=<名前>` で原因を見る。
   - postgres-exporter が接続できない: `postgres_exporter` のロール(`pg_monitor` だけ。`postgres/init/30-monitoring.sh`)がない古いボリュームなら `make clean` が必要(infra/local/README.md)。
   - kafka-connect の `:9404` だけが down: JMX exporter の設定(`images/kafka-connect/jmx-exporter.yml`)か javaagent の読み込みの失敗。
2. 直るまでの間は、「まず見るもの」の SQL と status を手で確かめる。

## 本番では
- 上限(`max_slot_wal_keep_size`)は、PostgreSQL のディスクの大きさと WAL の書き込みの量から決める(「コネクタがどれだけの時間止まっても欠けないか」と「ディスクが溢れないか」の兼ね合い)。アラートの閾値は上限に対する割合なので、上限を変えても書き直さなくてよい。
- Alertmanager か監視基盤に通知をつなぐ。critical(`CdcSlotLost`・`CdcOutboxSlotMissing`・`CdcConnectorDown`)は当番に通知する。
