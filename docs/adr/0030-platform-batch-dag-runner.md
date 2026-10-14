# ADR-0030: 軽量 DAG ランナー(platform/batch)
- Status: Accepted
- Date: 2026-10-14
- Framework 参照章: 7.2, 7.4, 9.2, 14.1

## Context
ADR-0008 は、ワークフロー(Framework 7.2)をローカルでは `platform/batch` の軽量 DAG ランナーで代える(DAG の定義・前段の完了とファイルの到着での起動・Checkpoint・SLA の遅延の予測のメトリクス)と決めた。汎用のワークフロー基盤は本番で選ぶ。P08 で最初の利用者(`batch-etl`。ADR-0031)を作るので、次の点を決める。
- 実行の単位と、同じ実行を 2 つ動かさない方法。
- Checkpoint と再開(途中で kill されても、重複・欠損なく終える。ROADMAP P08 の DoD)。
- 起動の条件(時刻・前段の完了・ファイルの到着)と、止まっていた間の扱い。
- traceparent の引き継ぎ(ジョブの属性・マニフェスト)。
- SLA の遅延の予測の方法(前段の遅れによる待ちを含める)。

## Decision
### 1. 範囲
- DAG は Kotlin DSL(`dag("batch-etl.order-daily") { step("load", ingest) { ... } }`)で定義する。依存先には同じ DAG で先に定義したステップの参照だけを渡せるので、循環は作れない(Framework 7.2: cron の時刻の暗黙の連鎖を使わない)。
- 1 つのプロセスの中で動く部品(ライブラリ)で、専用のサーバ・UI は作らない。実行の記録は、利用者の DB(PostgreSQL)のスキーマ `batch` に置く(`BatchSchema` が専用の履歴の表でマイグレーションする。`inbox`・`outbox` と同じ)。
- 依存: `platform/security`(S3 の資格情報)と `platform/observability`(span)。MODULE_DESIGN §2 の一覧に足した。

### 2. 実行の単位とロック
- 実行は **DAG とパーティションの組**(`RunKey`)。日次の DAG のパーティションは業務の日(`2026-10-13`)、ファイルで動く DAG はファイルの名前(拡張子を除く)。1 つの組の実行の記録は 1 行で、やり直すと世代(`generation`)が増える。
- 同じ実行は、PostgreSQL の **advisory lock(セッション)** で 1 つだけにする。ロックの間は接続を 1 つ持ち続ける。プロセスが落ちれば接続が切れてロックが外れるので、表の行のロック(期限つき)と違い、期限の長さの調整が要らず、落ちた直後に再開できる。
- 実行の途中で DB との接続が切れるとロックも外れ、ほかのインスタンスが同じ実行を始めうる。そのためステップは再実行安全に作る(§3)。

### 3. Checkpoint・再開・再実行安全
- ステップの成功を記録し(`batch.batch_step`)、同じパーティションの再実行([RunMode.RESUME]。既定)は成功したステップを飛ばす。途中で kill された実行は RUNNING のまま残り、次の実行が落ちたステップから再開する。成功した実行の再実行は何もしない。
- [RunMode.RESTART] は、ステップの記録とパーティションの Checkpoint を消して始め直す(遅れて届いたデータでの作り直し。ADR-0031)。
- Checkpoint は 2 種類。
  - パーティションのステップの途中の位置(`StepContext.checkpointKey`)。やり直しで消える。
  - DAG 全体の Watermark(`StepContext.watermarkKey`。例 取り込んだ位置)。パーティションをまたいで残る。
- **ステップの結果と Checkpoint は同じトランザクションで確定する**(`JdbcBatchStore.writeCheckpoint(connection, ...)`。同じ DB のとき。自動コミットの接続では書かない)。Framework 7.4 の「半端な部分コミットを残さない」。
- ステップの結果を確定した後、成功の記録の前に落ちると、再実行は同じステップをもう一度実行する。そのため **ステップは再実行安全(Upsert かパーティションの置換)にする** のが前提(Framework 7.4)。ランナーはステップの冪等を代わりに保証しない。
- 依存先がすべて成功したステップから並べて実行する。ステップが失敗したら新しいステップは始めず、実行中のステップの終わりを待って FAILED にする。

### 4. 起動(`BatchScheduler`)
| 条件 | 起動するパーティション | 起動の情報(ジョブの属性) |
|---|---|---|
| 時刻(`Schedule.daily` / `weekly`。タイムゾーンの壁時計の時刻) | 直近の起動の時刻の日の N 日前 | `schedule.at` |
| 前段の DAG の完了(`Trigger.AfterDag`) | 前段が成功したパーティション。前段が作り直したら(世代が増えたら)後段もやり直す | 前段の実行の span の traceparent・Correlation ID、`upstream.dag`・`upstream.generation` |
| ファイルの到着(`Trigger.FileArrival`) | `{prefix}{name}.manifest.json` の `{name}` | マニフェストの traceparent・Correlation ID、`file.key`・`file.sha256`・`file.recordCount` など |

- 起動するのは、パーティションの実行の記録がないか、RUNNING のまま残っている(落ちた)ときだけ。**FAILED は自動で再実行しない**(運用に引き継ぐ。Framework 7.4。docs/runbooks/batch-rerun.md は P08 ⑤)。
- 時刻での起動は、止まっていた間の起動のうち **直近の 1 回だけ** を取り戻す。それより前の日は運用者が明示的に実行する(止まっていた間の全部を一度に動かして、下流に負荷をかけないため)。
- 前段の完了の判定は、メッセージではなく **実行の記録(DB)** を見る。前段と後段が別のプロセスでも、同じ DB を使っていれば後段を起動できる。前段の完了を探すのは既定 7 日前まで。
- ファイルの到着は、マニフェストの到着を完了の合図にする(Framework 9.2)。マニフェストの形式(`contracts/files/manifest.v1.schema.json`)・データファイルがあること・`sizeBytes` を確かめ、誤りはログに残して起動しない(同じ理由は 1 回だけ)。件数と SHA-256 の照合は、データファイルを読むステップが行う(P09 の取り込み)。置き場は S3 互換ストレージ(`S3ObjectSource`。読み取り専用の identity)。P09 の `platform/file-transfer` ができたら、S3 の接続はそちらに寄せる。
- 複数のインスタンスで動かしても、§2 のロックで同じ実行は 1 つだけになる。

### 5. トレース
- 実行の span(`{dag} run`)の親は、起動の情報の traceparent(マニフェスト・前段の DAG の実行の span)。なければ新しいトレースを始める。ステップは `{dag} {step}` の子の span。
- 起動の情報は実行の記録に保存し、**再開しても最初の起動の traceparent と Correlation ID を使う**(kill の前と後が同じトレースにつながる)。実行の span の traceparent も記録し(`run_traceparent`)、後段の DAG の span の親にする。
- span・メトリクスの属性にパーティション(日付・ファイル名)は入れない(メトリクスの系列が増え続けるため)。span には `batch.partition` を入れる。

### 6. SLA の遅延の予測(`SlaMonitor`)
- DAG に期限(`Sla`。例 `Sla.dailyAt(06:00, Asia/Tokyo)`: 業務の日の翌日 06:00)を定義する。
- ステップの所要時間の見込みは、成功した最近 20 回の実績の **p90**(最近傍順位法)。実績が 5 回に満たない間は、DAG の定義の見込み(`expected`)。
- 完了の予測は、DAG の依存をたどって次のように見込んだ、全ステップの終わりの最大。
  - 成功したステップ: 実際に終わった時刻。
  - 実行中のステップ: 始めた時刻 + 見込み。見込みを過ぎていれば今。
  - まだ始めていない(失敗してやり直す)ステップ: 依存先が終わる見込みの時刻(と今の遅い方)に始め、見込みだけかかる。
- **前段の遅れによる待ちを含める**: 前段のステップが見込みを過ぎると、後段の開始の見込みが今に合わせて後ろにずれる。前段の DAG(`Trigger.AfterDag`)を待っている、まだ始まっていない実行も、前段の完了の予測を開始の見込みにして予測する。前段が遅れれば、後段は始まる前から遅れる見込みになる(単体テスト `SlaPredictorSpec`・`SlaMonitorSpec`)。
- 予測の対象は RUNNING と FAILED の実行(止まった実行も、時間がたつほど予測がずれる)と、前段を待つ実行。DAG ごとの余裕の最小値をメトリクス `eia.batch.sla.slack`(秒)に出し、**負になったらアラート**にする(完了の失敗の後ではなく、遅れると予測した時点。Framework 7.2)。ルールと promtool のテストは P08 ⑤。期限を過ぎた実行は余裕に入れず、終わったときに `eia.batch.sla.missed` で数える。
- 予測は `BatchScheduler` の確認の回ごと(既定 30 秒)に作り直す。

### 7. リトライ
- ステップごとの `RetryPolicy`。既定は 3 回(初回を含む。Framework 7.4)・5 秒から・上限 1 分・Full Jitter。リトライできない失敗(`DomainError.NonRetryable`)と、回数を使い切った失敗は運用に引き継ぐ(FAILED)。
- ステップの例外は、種類によらずリトライできる失敗にする(`step_threw`。ステップは再実行安全なので、やり直しても害がない)。1 回の試行の上限の時間(`timeout`)を超えたら `step_timed_out`。

### 8. メトリクス
| メトリクス | 種類 | 属性 |
|---|---|---|
| `eia.batch.runs` | counter | `batch.dag`・`batch.run.status` |
| `eia.batch.run.duration` | histogram(秒) | `batch.dag`・`batch.run.status` |
| `eia.batch.step.duration` | histogram(秒) | `batch.dag`・`batch.step`・`batch.step.status` |
| `eia.batch.step.retries` | counter | `batch.dag`・`batch.step`・`error.code` |
| `eia.batch.sla.slack` | gauge(秒) | `batch.dag` |
| `eia.batch.sla.missed` | counter | `batch.dag` |

## Alternatives Considered
- **汎用のワークフロー基盤(Airflow・Temporal・Argo Workflows など)をローカルの compose に入れる**: ADR-0008 で本番で選ぶとした。ローカルでは動かすものが増え、参照実装の主題(再実行安全・Checkpoint・SLA の予測)が基盤の設定に隠れる。不採用。
- **Quartz などのスケジューラのライブラリ**: 時刻での起動は得られるが、DAG・Checkpoint・SLA の予測は自分で作ることになる。時刻での起動は小さいので自作した。不採用。
- **ロックを表の行(期限つきのリース)で取る**: 落ちたときに期限が切れるまで再開できず、長いステップでは期限の延長が要る。advisory lock はセッションが切れれば外れる。不採用。
- **前段の完了を Kafka のイベントで知らせる**: 起動を逃さないためには、イベントの冪等な受信と、取りこぼしの照合が別に要る。後段は前段と同じ DB の実行の記録を見れば足りる。別の DB・別のチームの前段を待つ必要が出たら、完了のイベント(契約)を足す。不採用。
- **SLA の予測を平均で行う**: 長い裾(まれに遅い日)を過小に見積もり、アラートが遅れる。p90 にした。
- **止まっていた間の時刻の起動を全部取り戻す**: 長く止まっていた後に大量の実行が一度に始まる。直近の 1 回だけにし、残りは運用者が実行する。

## Consequences(トレードオフ)
- 実行の記録の DB は、ステップの結果の DB と同じにするのが前提(Checkpoint を同じトランザクションで書くため)。別の DB に書くステップは、結果の側で冪等にする(Upsert・置換)。
- ロックの間、DB の接続を 1 つ使い続ける。同時に動く実行の数だけ接続が要る。
- 実行の記録・ステップの記録は消さない(日次の DAG なら 1 年で数百行)。所要時間の実績もこの記録から取る。
- 予測は前段の DAG の定義が同じプロセスにあるときだけ前段を待つ実行を見積もる(ほかのプロセスの DAG の見込みは分からない)。
- P08 ③〜⑥ で最初の利用者(`batch-etl`)・アラート・Runbook・E2E を足す。

## 改訂履歴
