# ADR-0027: レガシーの CDC の照合と再同期
- Status: Accepted
- Date: 2026-10-08
- Framework 参照章: 7.3, 8.2, 13, 14, 16

## Context
P06 ⑤ で、レガシーの受注表の変更を Debezium で取り込み、Anti-Corruption Layer(legacy-order-acl)で `sales.legacy-order.changed.v1` に出すようにした(ADR-0026)。Framework 8.2 は「定期整合性検証(件数・ハッシュ)でドリフト検知、乖離時は部分 Snapshot で回復」を求める。次の点を決める必要がある。
- レガシーの表は今も変更され、出力はそれより遅れて届く。そのまま比べると、処理中の変更がずれとして報告される。
- 何と何を、どの形で比べるか。変換できずに DLQ に送った行をどう扱うか。どのキーがずれているかを特定できるか。
- ずれを見つけたときに、自動で直すか、人が判断するか。
- 照合のためにレガシーの DB を読むこと(Shared Database の禁止との関係)と、レガシーの DB の負荷。

## Decision
### 1. 構成とレガシーの DB を読む例外
- 照合は legacy-order-acl の中で動かす(定期の照合のジョブと、手動の `legacy-order-acl reconcile`)。出力のトピックの所有者が、出力の正しさを確かめる。
- **ACL のサービスが、レガシーの DB を読み取り専用で読むことを例外として認める**(CLAUDE.md §8 の Shared Database の禁止の例外)。
  - レガシーは改修できない外部のシステムで、照合の正(source of truth)はレガシーの表にしかない。Debezium と同じく、連携のための最小限の読み取りで、業務のデータを書き換えない。
  - 読むのは専用のロール **`eiaf_reconcile`** だけ。権限は `t_juchu` の SELECT だけ(DBA の作業。legacy-sim の V3)。ロールはクラスタの初期化(`postgres/init/40-reconcile.sh`)で作る。
- 構成(application の Port と adapters の実装):

  | Port | 実装 | 役割 |
  |---|---|---|
  | `LegacySource` | `JdbcLegacySource` | レガシーを 1 つのスナップショットで読む・CDC の取り込みの位置を待つ |
  | `PublishedLegacyOrders` | `KafkaPublishedLegacyOrders` | ACL が追いつくのを待って、出力の最新の状態を読む |
  | `Fingerprints` | `Sha256Fingerprints` | 正規の文字列の SHA-256 |
  | — | `ReconcileLegacyOrdersService`(application) | 比べる・分類する・比べ直す |

### 2. 比べる時点のそろえ方
1回の比較の手順:
1. **レガシーを読む**: `REPEATABLE READ`・読み取り専用のトランザクションで、最初の文で位置 X(`pg_current_wal_lsn()`。レプリカでは `pg_last_wal_replay_lsn()`)を取り、同じスナップショットで行を読む。スナップショットで見えるトランザクションは、すべて X より前にコミットしている。**読み終えたら、すぐにコミットして接続を閉じる**。手順 2 以降の待ちの間にトランザクションを開けたままにしない(古いスナップショットが残ると、レガシーの DB の VACUUM が止まる)。`ReconcileIT` で、待ちの間に照合のロールのセッションが「トランザクションの中」でもスナップショット(`backend_xmin`)を持ってもいないことを確かめた。
2. **CDC が X より先まで取り込むのを待つ**: スロット `legacy_juchu` の `confirmed_flush_lsn` が X 以上になるまで。Connect はレコードを Kafka に書いた後にオフセットを確定し、スロットを進めるので、この時点で X までの変更は生の CDC のトピックにある。変更がない間も heartbeat(`pg_logical_emit_message`。ADR-0026 §3)でスロットが進む。
3. **ACL が追いつくのを待つ**: 生の CDC のトピックの今の末尾 E を取り、ACL の Consumer Group のコミット済みのオフセットが E に届くまで待つ(コミット = 発行の完了。ADR-0026 §6)。
4. **出力を読む**: 出力のトピックの今の末尾までを最初から読み、キーごとに最後の値を残す(compacted。tombstone は削除)。
5. **比べ直す**: 食い違ったキーだけを、時間をおいて(既定 30 秒)手順 1〜4 で比べ直す(`WHERE col_02 = ANY(...)`)。X の近くでコミットしたトランザクションなど、時点のわずかなずれを除く。それでも食い違うキーを **ずれ** とする。
- 手順 2・3 の待ちが上限(既定 2 分)を超えたら、**ずれではなく検査の失敗** にする(Connect・ACL が止まっているときに、大量のずれを報告しない)。最後に成功した時刻が進まないことで知らせる(§5)。
- `ReconcileIT` で、レガシーを読んだ後(待ちの前)にレガシーを更新・登録・削除しても、ずれと判定しない(1 回目の比較では食い違い、比べ直しで一致する)ことを確かめた。

### 3. 何と何を比べるか
- **同じ形どうしで比べる**: レガシーの行は、ACL と同じ変換の規則(domain の `LegacyOrderTranslation`)を通す。出力の値は契約の型から同じ domain の型(`LegacyOrder`)にする。両方を `LegacyOrderFingerprint` の正規の文字列にし、SHA-256 で比べる。項目の順序と書式を固定し、区切りの文字はエスケープする。変更の位置(`source`)は含めない(送り直しで変わるため)。
- **分類**:

  | 分類 | 条件 | 扱い |
  |---|---|---|
  | 一致 | ハッシュが同じ、または両方にない | — |
  | 変換できない | レガシーの行が変換の規則に合わない(同じ行は DLQ に入る) | **既知の差** として別に数え、ずれにしない。出力に古い状態が残っていても同じ。アラートは DLQ の `LegacyAclDeadLetters`(ADR-0026 §10) |
  | `MISSING` | レガシーにあるが、出力にない(または tombstone) | ずれ |
  | `STALE` | 両方にあるが、ハッシュが違う | ずれ |
  | `EXTRA` | レガシーにないが、出力に値がある | ずれ |
- **キーごとのハッシュ** を持ち、どのキーがずれているかを特定する(部分の再同期の対象)。全体の SHA-256(キーの順に「キー:ハッシュ」を並べたもの)は、ログと報告に残す(照合の結果を比べやすくするため)。
- 規模: 毎回、表の全件と出力の全体を読む。模擬の規模では十分だが、本番の大きな表では、キーの範囲で分けて順に照合するか、変更の多い範囲から照合するなどの分割が要る(実装は必要になったとき)。

### 4. レガシーの DB の負荷の条件
- **本番ではレプリカから読む**(`LEGACY_ORDER_ACL_RECONCILE_DB_URL` をレプリカにする)。位置はレプリカで適用済みの位置(`pg_last_wal_replay_lsn()`)を使う。スロットはプライマリにだけあるので、スロットの位置だけはプライマリから読む(`LEGACY_ORDER_ACL_RECONCILE_SLOT_DB_URL`。カタログのビューを 1 行読むだけ)。
- **ロールの設定で上限を強制する**(`postgres/init/40-reconcile.sh`。ロールに付けるので、アプリの設定の誤りでは外れない):

  | 設定 | 値 | 理由 |
  |---|---|---|
  | `CONNECTION LIMIT` | 4 | 1 回の照合は同時に 2 本まで使う(スナップショットとスロット)。定期の照合と手動の照合が重なっても足りる数 |
  | `statement_timeout` | 30s | 1 回の問い合わせの時間の上限。超えたら一時的な失敗(SQLState 57014)にし、次の間隔でやり直す |
  | `idle_in_transaction_session_timeout` | 60s | 万一トランザクションを開けたままにしても、DB の側で切る(VACUUM を守る二重の防御) |
  | `default_transaction_read_only` | on | 書き込みをしない |
- 接続はプールせず、使うたびに開いて閉じる(照合は 15 分ごとで、接続を持ち続けない)。
- **間隔は業務の負荷を見て決める**(既定 15 分。`LEGACY_ORDER_ACL_RECONCILE_INTERVAL`。ローカルは確かめやすいよう 2 分)。夜間の締め処理など、レガシーの負荷の高い時間帯を避ける運用も、本番の判断に含める。

### 5. 定期実行と監視
- 照合は ACL の中で、起動の直後と間隔ごとに動かす(変換の読み取りのスレッドとは別。照合の待ちの間も変換を止めない)。手動の照合は `legacy-order-acl reconcile`(終了コード 0 = 一致 / 1 = ずれ / 2 = 設定の誤り / 3 = 検査の失敗。ずれのキーを 1 行ずつ出す)。
- メトリクス(`ReconcileMetrics`。監査のアンカーと同じ形): `eia.reconcile.last_success`・`eia.reconcile.interval`・`eia.reconcile.drift_keys{kind}`・`eia.reconcile.unconvertible_keys`・`eia.reconcile.checks{outcome}`。比べ終えれば、ずれがあっても「成功」とする(検査ができたことと、ずれがあることを分ける)。
- アラート(`prometheus/rules/reconcile.rules.yml`。promtool のテストは `reconcile.test.yml`。閾値を変えるとテストが失敗することを確かめた):

  | アラート | 重大度 | 条件 |
  |---|---|---|
  | `LegacyReconcileStale` | warning | 最後の成功から、間隔の 3 倍を超えた状態が 5 分続く。変更がない時間帯にも照合は成功するので、静かな時間帯に誤報を出さない |
  | `LegacyReconcileDrift` | warning | ずれがある状態が 30 分続く(既定の間隔で照合 2 回分)。照合が成功している間だけ判定する(古い値で判定しない) |
  | `LegacyReconcileDriftOverLimit` | critical | ずれが自動の再同期の上限(100 件)を超えた |
- Grafana の **CDC — Legacy** に照合の行を加えた。対応は `docs/runbooks/legacy-order-acl.md#照合`。

### 6. 再同期(P06 ⑥b)
- **部分の再同期を自動で行う。1 回の照合で取り直すキーは 100 件まで**。上限を超えたら自動では動かず、`LegacyReconcileDriftOverLimit` で人が判断する(仕組みの問題を疑い、全体の再同期を検討する)。環境変数で自動を止められるようにする。
  - Incremental Snapshot は今の状態を読み直すだけなので、何度実行しても結果は同じ(冪等)。自動にしても、レガシーの業務のデータは変わらない。
- 取り直しは signal 表の `execute-snapshot` に条件(`additional-conditions` の `col_02 IN (...)`)を付けて、ずれたキーだけにする。**signal 表への INSERT は、専用の最小の権限のロール `eiaf_resync`(signal 表の INSERT だけ)で行う**(Debezium のロールは使わない)。
- `EXTRA`(レガシーにない行の値が出力に残る)は、Snapshot では削除を作れないため、**照合が出力に tombstone を書く**。
  - 出力のトピックの所有者は ACL なので、責務の境界を越えない。書く直前に、そのキーがレガシーにないことをもう一度確かめる。
  - 前提: レガシーは注文番号を再利用しない(採番の連番から作る)。再利用されると、照合の tombstone が新しい注文を消しうる。
  - **照合が書いた tombstone には出どころの目印を付ける**: `ce_source` を `/sales/legacy-order-acl/reconcile`(変換が書くものは `/sales/legacy-order-acl`)にする。消費者と運用が、CDC の削除と照合の削除を見分けられるようにする。
- 手順の全体(ずれを見つけたとき、部分・全体の再同期、レガシーの DDL の変更)は `docs/runbooks/cdc-resync.md`。

## Alternatives Considered
- **時点をそろえずに比べ、食い違いの割合に閾値を設ける**: 処理中の変更の量で結果が揺れ、本当のずれと区別できない。不採用。
- **待ちの間もレガシーのトランザクションを開けたままにして、最後に比べる**: 古いスナップショットが残り、レガシーの DB の VACUUM を止める(更新の多い表ほど影響が大きい)。不採用。
- **生の CDC のトピックと出力を比べる(レガシーの DB を読まない)**: CDC が取りこぼした変更(スロットの無効化など)を検出できない。照合の目的(レガシーを正として確かめる)を満たさない。不採用。
- **件数と全体のハッシュだけを比べる**: どのキーがずれているか分からず、部分の再同期ができない。不採用(全体のハッシュは報告に残す)。
- **Kafka の時刻(タイムスタンプ)で時点をそろえる**: レガシーのコミットの時刻と、Kafka に書かれた時刻は対応しない(Snapshot・送り直しで大きくずれる)。LSN で比べる方が確実。不採用。
- **照合を別のサービスにする**: 出力の型・変換の規則・メトリクスを ACL と共有する必要があり、二重に持つことになる。ACL の中に置く。不採用。
- **ずれは常に人が判断する(自動で直さない)**: 少数のずれでも人の手を待ち、回復が遅れる。上限つきの自動の方が、安全と回復の速さの釣り合いが良い。不採用。

## Consequences(トレードオフ)
- ACL のサービスがレガシーの DB の資格情報を持つ(読み取り専用・ロールの上限つき)。資格情報は SecretProvider(`LEGACY_RECONCILE_DB_PASSWORD`)で渡す。
- 照合の 1 回に、少なくとも比べ直しの待ち(30 秒)と、取り込み・処理の待ちがかかる。間隔はそれより十分長くする。
- 表の全件を読むため、表が大きくなると照合の時間とレガシーの負荷が増える(§3 の分割が要る)。
- 照合が出力に tombstone を書くことで、出力の書き手が 2 つになる(変換と照合)。`ce_source` で見分ける。

## 改訂履歴
- 2026-10-08: P06 ⑥a で作成。§1〜§5(照合・監視)を実装した。§6(再同期)は P06 ⑥b で実装する。
- 2026-10-09: P06 ⑥b で §6(再同期)を実装した。
  - 確認: Debezium 3.6.3 の `additional-conditions` の `col_02 IN ('J000000002','J000000004')`(CHAR(10) の列)で、指定した 2 件だけが `source.snapshot=incremental` で取り直されることを確かめた。Apicurio 3.3.3 の `POST …/versions?dryRun=true` は、グループ `cdc-raw` のアーティファクトに対して、互換な変更で 200(版は作られない)、互換でない変更で 400 と理由を返すことを確かめた(DDL の事前の確認に使う)。
  - `ResyncLegacyOrdersService`(上限 100 件。超えたら何もしない)、`JdbcSnapshotRequests`(`eiaf_resync`。JSON はライブラリで組み立て、注文番号は SQL の文字列のリテラルで `'` を `''` にする)、`KafkaReconcileTombstones`(`ce_source=/sales/legacy-order-acl/reconcile`)。signal 表はプライマリにだけ書けるので、再同期の接続先はスロットと同じ(`…_RECONCILE_SLOT_DB_URL`)。
  - `eiaf_resync`: `postgres/init/40-reconcile.sh`(接続数 2・statement_timeout 10s)、権限は legacy-sim の V4(signal 表の INSERT と、スキーマ `eiaf_cdc` の USAGE だけ)。
  - 自動の再同期は `LEGACY_ORDER_ACL_RECONCILE_AUTO_RESYNC`(既定 true)で止められる。手動は `legacy-order-acl reconcile`(`--dry-run` で比べるだけ)。メトリクス `eia.reconcile.resynced_keys{action}`・`eia.reconcile.resyncs{outcome}`。
  - 手順の全体は `docs/runbooks/cdc-resync.md`(ずれを見つけたとき・部分と全体の再同期・レガシーの DDL の変更)。
