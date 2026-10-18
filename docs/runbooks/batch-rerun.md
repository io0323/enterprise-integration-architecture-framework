# Runbook: バッチの再実行(batch-etl・platform/batch)

## 対象
- 注文の分析のバッチ `batch-etl`(ADR-0031)。
  - 日次の DAG `batch-etl.order-daily`: 毎日 01:00 JST に前日の分を取り込み、日次のパーティションを作る。SLA の期限は翌日 06:00 JST。
  - 週次の照合 `batch-etl.order-reconcile`: 毎週月曜 07:00 JST に起動し、order-service の日次の集計と、前日から 38 日前までを比べる。
  - 日次の売上ファイル `batch-etl.sales-file`(INT-SALES-004。ADR-0031 §8): 日次の DAG の同じ日の成功で起動し、ファイル授受のバケットの受け口
    (`eiaf-file-exchange` の `inbox/sales-daily/`)に置く。SLA の期限は 07:00 JST。受け取り以降は file-exchange(`docs/runbooks/file-exchange.md`)。
- DAG の実行の仕組み `platform/batch`(ADR-0030):
  - 実行は DAG とパーティションごとに 1 つだけ。
  - 成功したステップは再実行で飛ばす。
  - FAILED は自動で再実行しない。

| アラート | 重大度 | 見る節 |
|---|---|---|
| `BatchRunFailed` | warning | [実行が失敗したとき](#実行が失敗したとき) |
| `BatchSlaAtRisk` / `BatchSlaMissed` | warning | [SLA に遅れる見込みのとき](#sla-に遅れる見込みのとき) |
| `BatchEtlLateDaysPending` | warning | [遅れて届いたデータで古い日が変わったとき](#遅れて届いたデータで古い日が変わったとき) |
| `BatchEtlReconcileDrift` | warning | [照合でずれが見つかったとき](#照合でずれが見つかったとき) |
| `BatchEtlIngestLagNearRetention` / `BatchEtlIngestStale` | warning | [取り込みが保持期間に近づいたとき](#取り込みが保持期間に近づいたとき) |
| `BatchEtlIngestLagCritical` | critical | [取り込みが保持期間に近づいたとき](#取り込みが保持期間に近づいたとき) |
| `BatchEtlEventsLost` | critical | [保持期間を超えた回復](#保持期間を超えた回復) |

## まず見るもの
- ダッシュボード **Batch — Order analytics**(Grafana のフォルダ EIAF)。
  - SLA の余裕・実行の結果・ステップの所要時間(p90)。
  - 取り込み残しの経過時間と保持期間。
  - 作り直し・照合。
- ログ(`make logs SERVICE=batch-etl`)。実行の失敗は `<dag>/<partition> の実行が失敗しました(...)` が ERROR で出る。注文の値は出ない。
- 実行の記録(`batch_etl` の DB。スキーマ `batch`):
  ```sql
  SELECT dag_id, partition_key, status, generation, started_at, finished_at FROM batch.batch_run ORDER BY started_at DESC LIMIT 20;
  SELECT step_name, status, attempts, started_at, finished_at FROM batch.batch_step
   WHERE dag_id = 'batch-etl.order-daily' AND partition_key = '2026-10-13';
  ```
- トレース(Tempo)は `batch-etl.order-daily run` の span から探す。再開した実行も、最初の起動と同じトレースにつながる(ADR-0030 §5)。

## コマンド
`make up PROFILE="order batch"` で起動した基盤で、同じイメージ・同じ環境のコンテナを 1 回だけ動かす(アプリのロールだけ。所有者のパスワードは渡さない)。

| コマンド | 内容 |
|---|---|
| `make batch-etl ARGS="run 2026-10-13"` | その日の日次の DAG を実行する。失敗・中断した実行は、失敗したステップから再開する。成功した日は何もしない |
| `make batch-etl ARGS="run 2026-10-13 --restart"` | その日を最初から作り直す(世代が 1 増える)。取り込みは冪等、パーティションは置換なので、何度実行しても結果は同じ |
| `make batch-etl ARGS="sales-file 2026-10-13"` | その日の売上ファイルを置く(日次の DAG が成功している日だけ)。失敗・中断した実行は再開する。成功した日は何もしない |
| `make batch-etl ARGS="sales-file 2026-10-13 --restart"` | その日の売上ファイルを新しい版(名前の連番が 1 増える)で置き直す |
| `make batch-etl ARGS="reconcile 2026-10-19"` | その日を実行の日として照合する。一致しない日があれば終了コード 3 |
| `make batch-etl ARGS="skip-lost-events"` | 保持期間を過ぎて消えたイベントの件数を表示する(何も変えない)。`--confirm` で消えた範囲を飛ばす |

- 同じ実行(DAG とパーティション)がほかで動いていれば、何もしないで終わる(ロック)。常駐の `batch-etl` が動いていても実行してよい。
- 実行は冪等なので、迷ったら `run <日付>` をもう一度実行してよい。

## 実行が失敗したとき
`BatchRunFailed`。ステップがリトライ(3 回)の後も失敗し、運用に引き継がれた(Framework 7.4)。成功したステップの結果と Checkpoint は残っている。

1. ログと `batch.batch_step` で、どのステップが何で失敗したか確かめる(`error.code`)。

   | 失敗 | 原因 | 対応 |
   |---|---|---|
   | `ingest` が `unavailable` | Kafka・Schema Registry・分析の DB が使えない | 基盤が戻ってから `run <日付>` |
   | `ingest` が「保持期間を過ぎて消えています」 | 取り込む前にイベントが消えた | [保持期間を超えた回復](#保持期間を超えた回復) |
   | `transform` が `unexpected` | SQL の誤り・権限 | 修正をデプロイしてから `run <日付>` |
   | `reconcile` が `unavailable` | order-service・Gateway・Keycloak に問い合わせられない(その日は UNVERIFIED) | 戻ってから `reconcile <日付>` |
2. 原因を除いてから `make batch-etl ARGS="run <日付>"`。失敗したステップから再開する。
3. 前の日も失敗していれば、古い日から順に実行する(遅れて届いたデータの作り直しは、実行の日から 31 日前までが対象。ADR-0031 §5)。

## 売上ファイルを置き直すとき
`BatchRunFailed`(`batch_dag="batch-etl.sales-file"`)、または受け手(file-exchange)が quarantine に移したとき。

- **置き場(SeaweedFS)が止まっていた**: 売上ファイルの DAG だけが失敗する。注文の取り込みの DAG(`order-daily`)とその SLA には影響しない
  (別の DAG。P09 ⑥ の統合テスト `BatchEtlAppIT` で確かめた)。置き場が戻ってから `make batch-etl ARGS="sales-file <日付>"`。
  失敗したステップから再開し、同じ名前(同じ版)で置く。
- **受け手が quarantine に移した**(改竄・欠損・形式の誤り。`FileExchangeQuarantined`): 原因を直してから
  `make batch-etl ARGS="sales-file <日付> --restart"` で **新しい版** を置く。同じ名前で置き直すと、受け手は受け取り済みの名前として扱い、
  取り込まない(`FileExchangeNameReused`。docs/runbooks/file-exchange.md#名前の使い回し)。
- その日の日次の DAG を作り直した(`run <日付> --restart`)ときも、売上ファイルは自動では置き直さない。必要なら `sales-file <日付> --restart`。
- 置いた版は実行の記録で確かめる(`generation` が名前の連番。受け取りは file-exchange の `exchange.received_file`):
  ```sql
  SELECT partition_key, status, generation, finished_at FROM batch.batch_run WHERE dag_id = 'batch-etl.sales-file' ORDER BY started_at DESC LIMIT 10;
  ```

## SLA に遅れる見込みのとき
`BatchSlaAtRisk`。期限(06:00 JST)までに終わらない見込み(前段のステップの遅れによる待ちを含む予測。ADR-0030 §6)。`BatchSlaMissed` は期限を過ぎて終わった。

1. ダッシュボードの「ステップの所要時間(p90)」と、`batch.batch_step` の実行中のステップを見る。
2. 遅れの原因で対応する。
   - **取り込み残しが多い**(止まっていた後など): 待つ。分析の利用者に遅れを知らせる。
   - **遅れて届いたデータで、作り直す日が多い**(ログの「作り直した」・メトリクス `eia_batch_etl_partitions_rebuilt_total{batch_etl_late="true"}`): 待つ。
   - **ステップが止まっている**(実行中のまま進まない): ログで止まっている箇所を調べる。プロセスを再起動すると、実行は RUNNING のまま残り、次の起動で再開する。
3. 遅れが続くなら、p90 の実績が増えている。ステップの見込み(`expected`)・起動の時刻(`BATCH_ETL_DAILY_AT`)・期限(`BATCH_ETL_SLA_AT`)を見直す。

## 遅れて届いたデータで古い日が変わったとき
`BatchEtlLateDaysPending`。31 日より前の日のデータが変わった(遅れて届いた受け付け・後からの遷移)。締めた後の数字を自動で変えないため、作り直していない(ADR-0031 §5)。

1. どの日か確かめる: `SELECT business_date, marked_at FROM etl.dirty_day ORDER BY business_date;`
2. その日を作り直してよいか、分析の利用者(締めの担当)に確かめる。
3. 作り直すなら、日ごとに `make batch-etl ARGS="run <日付> --restart"`。印が消え、アラートが解消する。
4. 作り直さないなら、印は残り、アラートも続く。判断を記録してから印を消す: `DELETE FROM etl.dirty_day WHERE business_date = '<日付>';`

## 照合でずれが見つかったとき
`BatchEtlReconcileDrift`。週次の照合で、比べ直しても order-service の日次の集計と一致しない日がある(ADR-0031 §6)。31 日以内の日は、比べ直しの前に作り直している。そのため、作り直しで直るずれ(遅れて届いたデータ)は DRIFT にならない。

1. どの日か確かめる:
   ```sql
   SELECT business_date, source_count, mart_count FROM mart.reconciliation
    WHERE run_date = (SELECT max(run_date) FROM mart.reconciliation) AND outcome = 'DRIFT';
   ```
2. 件数で原因の見当を付ける。
   - **分析の件数が少ない**(取りこぼし):
     - DLQ(`sales.order.*.v1.dlq`)に、その日の注文のイベントがないか見る。読めない値は DLQ に送って先に進むため。原因を直してから、DLQ を戻す(docs/runbooks/event-dlq-replay.md)。
     - 保持期間を超えて消えた範囲がないか見る([保持期間を超えた回復](#保持期間を超えた回復))。
   - **件数は同じでハッシュが違う**(状態・金額のずれ): 遷移のイベントが DLQ にないか見る。
   - **分析の件数が多い**: 区切り(日本時間)の誤り・二重の発行を疑う。取り込みの冪等のキー(注文 ID)を確かめる。
3. 原因を除いたら、その日を作り直してから照合し直す:
   ```bash
   make batch-etl ARGS="run <日付> --restart"
   make batch-etl ARGS="reconcile <実行の日> --restart"
   ```
4. イベントが消えていて取り戻せない日は、DRIFT のまま残る(下の節)。

## 取り込みが保持期間に近づいたとき
注文のイベントのトピックの保持期間は **14 日**(`infra/local/kafka/topics.conf`。ADR-0031 §7)。取り込み残しの最も古いイベントがこれを過ぎると消え、取りこぼしになる。

| アラート | 意味 |
|---|---|
| `BatchEtlIngestLagNearRetention` | 保持期間の 50% を超えた(14 日なら 7 日) |
| `BatchEtlIngestLagCritical` | 保持期間の 75% を超えた(10.5 日) |
| `BatchEtlIngestStale` | 取り込み残しがあるのに、2 日以上取り込んでいない(Kafka 側から見る。batch-etl が止まっていても出る) |

1. batch-etl が動いているか確かめる(`make ps`・`/health/ready`)。止まっていれば起動する。起動すると、直近の分の日次の DAG が動き、取り込みが末尾まで進む。
2. 動いているのに進まないなら、[実行が失敗したとき](#実行が失敗したとき)に従う(`ingest` の失敗)。
3. 取り込みだけを急ぐなら、今日の前日の分を実行する(取り込みは日付によらず末尾まで進む): `make batch-etl ARGS="run <前日>"`。
4. 間に合わない見込みなら、トピックの保持期間を一時的に延ばす。`topics.conf` を変え、`kafka-topics` を動かし直す。アラートの閾値は保持期間に追従する。

## 保持期間を超えた回復
`BatchEtlEventsLost`(critical)。取り込む前にイベントが保持期間を過ぎて消えた。取り込みは黙って先に進まず、失敗し続ける(ADR-0031 §4)。

**消えたイベントを埋め戻す手段は、今はない**(Issue #116)。
- order-service はイベントを再発行できない。
- 分析は order-service の DB を直接読まない。
- 日次の集計の API は件数とハッシュだけを返す。

影響した日は、ずれ(DRIFT)として受け入れるしかない。

1. 消えた件数を確かめる(何も変えない):
   ```bash
   make batch-etl ARGS="skip-lost-events"
   ```
   トピック・パーティションごとに、消えた件数が WARN で出る。
2. 影響を記録し、分析の利用者に知らせる(どの期間のどのイベントが欠けたか)。
3. 消えた範囲を飛ばして、取り込みを再開する:
   ```bash
   make batch-etl ARGS="skip-lost-events --confirm"
   make batch-etl ARGS="run <前日>"
   ```
4. 照合で、影響した日を見つける。38 日前より古い日は、照合の範囲の外なので出ない:
   ```bash
   make batch-etl ARGS="reconcile <今日>"
   ```
   一致しない日(終了コード 3。`mart.reconciliation` の DRIFT)が、取りこぼした日。
5. 埋め戻しの手段(#116)ができるまで、その日は DRIFT のまま残る。`BatchEtlReconcileDrift` は次の照合でも出る。判断を記録して受け入れる。
6. 再発を防ぐ。止まっていた原因を除き、保持期間(14 日)と、止まりうる期間を見直す(ADR-0031 §7)。
