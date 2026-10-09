# Runbook: イベント・コマンドの DLQ と Replay

## 対象
`platform/messaging-kafka` の `EventConsumer` で読む連携(業務イベント `{domain}.{entity}.{event}.v{n}`・コマンド `{domain}.{entity}.cmd-{command}.v{n}`)。
処理できないメッセージは `{topic}.dlq` に隔離され、本流は止まらない(Framework 6.5。ADR-0028)。
設計は ADR-0028(§2 失敗の扱い・§3 冪等消費・§6 Replay)。

| アラート | 重大度 | 見る節 |
|---|---|---|
| `EventDeadLetters` | warning | [DLQ に入ったとき](#dlq-に入ったとき) |
| `EventConsumerStalled` | warning | [Consumer が進まないとき](#consumer-が進まないとき) |
| `EventConsumerUnavailable` | warning | [Consumer が進まないとき](#consumer-が進まないとき) |
| `EventConsumerLagHigh` | warning | [Consumer が進まないとき](#consumer-が進まないとき) |

**対象外**: 生の CDC の DLQ(`_cdc.*.dlq`)は戻さない(古い状態で上書きするため)。`docs/runbooks/legacy-order-acl.md` と `docs/runbooks/cdc-resync.md`(signal 表の部分の再同期)に従う。`make dlq-replay` も拒否する。

## まず見るもの
- サービスのログ(`make logs SERVICE=<service>`)。DLQ に送ったときは `処理できないメッセージを DLQ に送りました(reason=..., detail=..., attempts=..., <topic> partition=... offset=...)` が WARN で出る。値は出ない。
- メトリクス(Prometheus / Grafana の Explore): `eia_consumer_dead_letters_total{reason}`・`eia_consumer_messages_total{outcome}`・`eia_consumer_retries_total`・`eia_consumer_unavailable_total{error_code}`。
- lag: `kafka_consumergroup_lag_sum{consumergroup="<group>"}`(kafka-exporter)。
- ready: サービスの `/health/ready` が 503 なら、Unavailable で読み直している。

## DLQ に入ったとき
`EventDeadLetters`。**本流は止まっていない**(同じパーティションの後続のメッセージは処理されている)。DLQ のメッセージの処理は終わっていない(業務の更新も冪等消費の記録もない)。

1. 対象を一覧にする(dry-run。何も送らない)。値は出ない。
   ```bash
   make dlq-replay ARGS="--topic inventory.stock.cmd-reserve.v1.dlq --limit 100"
   ```
   - 列: DLQ の `partition`・`offset`、キー(集約の ID・Saga ID)、`ce_id`、`ce_type`、`reason`、DLQ に入った時刻、一度戻したことがあるか。
   - DLQ のトピックは元のメッセージと **同じ機密区分**(値は受け取ったバイト列のまま)。値を読むのは調査に必要な人だけにし、外に持ち出さない。保持は 7 日(`infra/local/kafka/topics.conf`)。
2. 原因の種類で対応を決める(ADR-0028 §2)。

   | reason | 意味 | 対応 |
   |---|---|---|
   | `INVALID_HEADERS` | CloudEvents のヘッダの欠落・不正(`eiaf.dlq.detail` に項目の名前) | 発行側の誤り。発行側を直す。戻しても同じく失敗するので **戻さない**。必要なら発行側から送り直す |
   | `UNDECODABLE` | Avro として読めない(wire format の不正・未登録のスキーマ・書き手のスキーマと合わない) | 契約と実装の食い違い。スキーマの登録漏れ(`make schemas`)なら登録して戻す。値そのものが壊れていれば戻さない |
   | `UNEXPECTED_TOMBSTONE` | 値がない | 発行側の誤り。戻さない |
   | `UNEXPECTED_EXCEPTION` | 処理が想定しない例外を投げた(初回 + 3 回のリトライの後。attempts=4) | 受信側の実装の誤り。修正をデプロイしてから戻す |
   | 処理が返したコード(例 `UNKNOWN_SKU`) | 業務の検証の失敗(Rejected。attempts=1)、または一時的な失敗のリトライが尽きた(Transient。attempts=4) | 原因(マスタの不足・データの誤り・実装)を除いてから戻す |
3. 原因を除いたら戻す(次の節)。

### 戻す(Replay)
1. **戻す前に確かめること**
   - 原因が除かれていること(同じ原因なら、また DLQ に入るだけ。害はないが件数が増える)。
   - **後から届いた同じキーのメッセージとの順序**。DLQ のメッセージは、隔離の後に届いた同じキーのメッセージより後に処理される。受信側は業務キーで冪等で、状態の遷移を検査する(ADR-0028 §3)ので、古い指示は拒否されるか何もしない。Saga のコマンドでは、補償が先に終わっていれば、遅れて届いたコマンドは「取消済み」として拒否される(ADR-0029。P07 ③)。拒否された結果が業務として正しいかを、キーごとに確かめる。
   - 件数が多いときは、`--reason`・`--key`・`--failed-from` / `--failed-to`・`--partition` と `--from-offset` / `--to-offset` で絞り、少数で結果を確かめてから広げる。
2. dry-run で対象を確かめる(上と同じコマンドに条件を足す)。
   ```bash
   make dlq-replay ARGS="--topic inventory.stock.cmd-reserve.v1.dlq --limit 10 --reason UNKNOWN_SKU"
   ```
3. 送る(`--execute`)。`--limit` は必須(1〜1000)。上限を超えた分は対象にならない(出力の「上限で除いた件数」)。
   ```bash
   make dlq-replay ARGS="--topic inventory.stock.cmd-reserve.v1.dlq --limit 10 --reason UNKNOWN_SKU --execute"
   ```
   - キー・値・`ce_id` は DLQ のまま元のトピックに送る。`eiaf.dlq.*` のヘッダを外し、`eiaf.replay.*`(DLQ の位置・原因・戻した時刻)を付ける(INTEGRATION_STANDARDS §2)。
   - **同じものを 2 回戻しても、受信側は 1 回だけ処理する**(`ce_id` が同じなので processed_message で重複になる。保持は 14 日)。
   - 終了コード: 0 = 終えた、1 = 戻せなかったものがある(`FAILED` は送信の失敗でもう一度実行してよい。`SKIPPED_SOURCE_MISMATCH` は DLQ のレコードの元のトピックの記録が違うもので、手で入れたものなどを調べる)、2 = 実行できない(引数・拒否・接続)。
4. 結果を確かめる。
   - `eia_consumer_messages_total{outcome="processed"}` が戻した件数だけ増え、DLQ に新しいレコードが増えないこと。
   - 再び DLQ に入ったものは、一覧の `replayed_before` が `true` になる(`eiaf.replay.*` が付いたまま隔離される)。原因が除かれていない。`--exclude-replayed` で、初めて戻すものだけに絞れる。
5. DLQ のレコードは消さない(保持期間で消える)。戻したことは DLQ からは分からないので、作業の記録(チケット)に、実行したコマンドと出力の件数を残す。

## Consumer が進まないとき
`EventConsumerStalled`(lag があるのに、コミットしたオフセットが 10 分進まない)、`EventConsumerUnavailable`(アプリが基盤の障害で読み直している)、`EventConsumerLagHigh`(lag が 1,000 件を超えた状態が 10 分)。

1. サービスが動いているかを見る(`make ps`)。落ちていれば、ログで原因を確かめて起動する。再起動の後は最後のコミットの位置から読み直す(重複は冪等で吸収する)。
2. 動いていて `/health/ready` が 503 なら、**Unavailable**(自分の DB・Schema Registry が使えない)で読み直している(ADR-0028 §2)。
   - ログの `基盤が使えないため、... の後に未処理の位置から読み直します(group=..., <error_code>。N 回目)` で原因を確かめる。`eia_consumer_unavailable_total{error_code}` でも分かる。
   - `inbox_storage_unavailable`・`db_unavailable` などは DB、`schema_registry_unavailable` などは Apicurio。基盤を回復させれば、続きから順に処理される(**DLQ には入っていないので Replay は要らない**)。
3. ready が 200 で lag だけが増える(`EventConsumerLagHigh`)なら、処理が追いついていない。インスタンスを増やす(パーティション数まで)か、処理の時間(`eia_consumer_process_duration_seconds`)とリトライ(`eia_consumer_retries_total`)を確かめる。Transient のリトライが続くメッセージは、尽きれば DLQ に入る。
4. どれにも当たらないとき(プロセスはあるがメトリクスが出ない・ログが止まった)は、スレッドの停止を疑い、スレッドダンプを取ってから再起動する。

## 参考
- 失敗の種類と DLQ のヘッダ: ADR-0028 §2、INTEGRATION_STANDARDS §2。
- アラートのルールとテスト: `infra/local/prometheus/rules/consumer.rules.yml`・`consumer.test.yml`(`make alerts-test`)。
