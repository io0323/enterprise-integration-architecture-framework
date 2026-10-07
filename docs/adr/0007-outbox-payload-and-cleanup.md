# ADR-0007: Outbox のペイロード形式とテーブルのクリーンアップ
- Status: Accepted
- Date: 2026-09-25
- Framework 参照章: 6.3, 8.3, 13.2, 14.1

## Context
Framework 8.3 は「業務更新と Outbox 挿入を同一ローカルトランザクションで行い、Outbox には公開契約(スキーマ登録済み)の形式で書く」と定めている。
Debezium Outbox Event Router で Avro(Apicurio)のイベントを発行する方法として、(a) アプリが Avro にして保存する、(b) JSON で保存して Connect 側で変換する、の 2 通りがある。
どちらにするかで `platform/messaging-kafka` と `platform/outbox` のシリアライザの作り方が変わるため、P04 より前に決める必要がある。
また、Outbox テーブルは放置すると増え続けるため、クリーンアップの方針が必要になる。

## Decision
### 1. ペイロード形式
- アプリが Apicurio の Avro シリアライザでペイロードをバイト列にし、`outbox.payload`(`bytea`)に保存する。スキーマの検証と互換性検査はアプリ(書き込み時)と Registry で行う。
  - スキーマ ID は **Kafka ヘッダではなくペイロードの先頭に埋め込む**設定にする(Outbox 経由ではシリアライザが付けたヘッダが Kafka に届かないため)。
- Outbox テーブルの列:
  `id uuid PK, topic text, aggregate_type text, aggregate_id text, event_type text, payload bytea, traceparent text, correlation_id text, ce_id uuid, ce_source text, ce_time timestamptz, created_at timestamptz`
  - `id` と `ce_id` には同じ値(イベントの ID。UUIDv7)を入れる(改訂履歴 2026-10-07)。
  - `ce_specversion text NOT NULL DEFAULT '1.0'` を加えた(改訂履歴 2026-10-07 の ③b。アプリは値を書かない)。
- 表はスキーマ `outbox` に置き(`outbox.outbox`)、`platform/outbox` の `OutboxSchema` が専用の履歴の表で作る。アプリのロールには INSERT・DELETE と `id` 列の SELECT だけ、Debezium のロールには SELECT だけを付ける。Debezium が読む publication `eiaf_outbox` は Outbox の表だけを含む(改訂履歴 2026-10-07)。
- Debezium の設定(キー名は P06 で採用する Debezium のバージョンのドキュメントで確認して固定する。**確定した設定は `infra/local/kafka-connect/order-outbox.json` と改訂履歴 2026-10-07 の ③b**):
  - `transforms=outbox`(`io.debezium.transforms.outbox.EventRouter`)
  - `value.converter=org.apache.kafka.connect.converters.ByteArrayConverter`(アプリで Avro にしたバイト列をそのまま流す)。**③b で `io.debezium.converters.BinaryDataConverter` に改めた**(改訂履歴)
  - `route.by.field=topic`、`route.topic.replacement=${routedByValue}` で、`topic` 列の値(`{domain}.{entity}.{event}.v{n}`)をそのままトピック名にする
  - `table.field.event.key=aggregate_id`(パーティションキー)
  - `table.fields.additional.placement` で `traceparent`, `correlation_id`, `ce_*` を Kafka ヘッダに載せる(CloudEvents binary mode)

### 2. クリーンアップ
- **既定は「挿入直後に削除」パターン**: 業務更新・outbox への INSERT・同じ行の DELETE を、同一のローカルトランザクションで行う。Debezium は WAL から INSERT を読み取って発行し、DELETE イベントは Event Router が発行しない(Event Router の既定動作。コネクタ側も `tombstones.on.delete=false` にして tombstone を出さない)。テーブルには行が残らないため、肥大化も掃除用のジョブも発生しない。
  - 発行の確認は、Debezium の replication slot が WAL の位置を進めることで担保される。slot が進まない間は WAL が保持されるので、欠損は起きない。
  - 監視: replication slot の遅延(`pg_replication_slots` の `confirmed_flush_lsn` の差)と Connect のタスク状態を Prometheus で監視し、閾値超過をアラートにする(Runbook: `docs/runbooks/cdc-outbox-lag.md`)。
  - 実装は `platform/outbox` の `Outbox.append`(Exposed からは `appendOutbox`)。自動コミットの接続(トランザクションの外)では書かない。
- **調査・監査のために行を残す必要があるサービスだけ**、次の保持期間パターンを使う(サービスの ADR か設定で選択):
  - `created_at` で日次の RANGE パーティションにし、保持期間(既定 7 日。Kafka 側の保持期間と揃える)を過ぎたパーティションを `DETACH` → `DROP` するジョブを `platform/outbox` で提供する。
  - DROP する前に、該当期間の slot 位置が進んでいることを確認し、進んでいなければ DROP を中止してアラートを出す。
  - **実装は、行を残す必要のあるサービスが出てきたときに行う**(P06 では既定の方式だけを実装した)。P06 ② で検討した具体(封印した LSN での判定・DEFAULT パーティションを作らない理由・所有者の資格情報で実行すること)は Issue #76 に記録した。
- 監査の証跡は Outbox ではなく Audit(ADR-0008)に残す。

## Alternatives Considered
- JSONB で保存し、Connect 側で Avro に変換する: スキーマ検証がアプリの外に出てしまい、契約違反が発行時まで見つからない。Connect の変換設定にスキーマの知識が漏れる。不採用。
- Avro のバイト列ではなく Avro の JSON エンコードで保存する: 人が読みやすいが、Connect 側での再エンコードが必要になり、上と同じ問題がある。不採用。
- 定期バッチで発行済みの行を削除する(発行済みフラグ方式): 発行済みかどうかを Debezium から知る手段がなく、アプリ側でフラグを管理すると二重書き込みと同じ問題が生じる。不採用。
- アプリがポーリングして発行する(Transactional Outbox + Poller): Framework 8.1 のログベース CDC を既定とする方針に反する。不採用。

## Consequences(トレードオフ)
- テーブルに行が残らないため、「何が発行されたか」の確認は Kafka とトレースで行う。行を残したい場合は、保持期間パターンを選ぶ。
- アプリが Apicurio に依存する。ただし、書き込みに使うスキーマの ID は起動時にすべて解決してメモリに持ち、リクエストの処理中はレジストリに問い合わせない(ADR-0025 §3)。解決するまでは `/health/ready` を失敗にしてトラフィックを受けず、解決した後は Registry を止めても業務の更新(Outbox への保存)は続けられる。Registry に依存するのは、起動時の解決と、新しいスキーマの版の反映(`make schemas` とサービスの再起動)だけになる。
- ByteArrayConverter を使うため、Connect の SMT でペイロードの中身を加工することはできない(加工しないことを原則とする)。

## 改訂履歴
- 2026-10-02: P06 ① で、スキーマ ID を埋め込む形式(Apicurio 3 の既定と同じ 4 バイトの contentId)・スキーマの登録(`make schemas`。サービスは自動登録しない)・起動時の ID の解決を ADR-0025 で決めた。Consequences の「Registry の停止中は業務更新も失敗する」を、起動時に解決した ID を使い続ける方式に改めた。
- 2026-10-07: P06 ② で `platform/outbox` に既定の方式(INSERT の直後に DELETE)を実装した。
  - **`id` 列と `ce_id` 列には同じ値を入れ、その値は `EventMetadata.id` を UUIDv7 で作ったものにする**(`EventIds`。表の CHECK 制約で `ce_id = id` を強制する)。イベントの ID を 1 つにして、Outbox・WAL・Kafka(`ce_id` のヘッダ)・受信側(冪等の判定)をまたいで同じ値で追えるようにするため。UUIDv7 は先頭が時刻なので、主キーの B-tree の順序も保てる。列の構成は変えない。
  - 表はスキーマ `outbox` に置き、権限はアプリのロールに INSERT・DELETE・`id` 列の SELECT、Debezium のロールに SELECT だけを付ける。publication `eiaf_outbox` は Outbox の表だけを含む(Debezium には自動で作らせない)。
  - 保持期間の方式は実装せず、§2 の設計の記述は残した。実装は行を残す必要のあるサービスが出てきたときに行う(Issue #76)。
- 2026-10-07: P06 ③b で、order-service の Outbox を Debezium(3.6.3)で発行する設定を確定した(`infra/local/kafka-connect/order-outbox.json`。`make up PROFILE=order` が登録し、`OrderOutboxCdcIT` は同じファイルを登録して確かめる)。
  - **EventRouter**(3.6 では `debezium-connect-plugins` の jar。Postgres のコネクタの配布物に含まれる): `table.field.event.id=id`、`table.field.event.key=aggregate_id`、`table.field.event.type=event_type`、`table.field.event.payload=payload`、`route.by.field=topic`、`route.topic.replacement=${routedByValue}`。`table.fields.additional.placement` は **すべて header**(`ce_id`・`ce_source`・`event_type→ce_type`・`ce_time`・`ce_specversion`・`traceparent`・`correlation_id→correlationid`)。すべて header にすると、値は封筒(`payload` を持つ Struct)ではなくペイロードのバイト列そのものになる。EventRouter は `id` のヘッダ(`id` 列 = `ce_id`)も付ける。
  - **`ce_specversion` は列にした**(V2 マイグレーション。既定値 '1.0'、CHECK で '1.0' だけ)。Kafka Connect の `InsertHeader` の `value.literal` は値を型付きで解釈し、"1.0" が数の 1 になる(引用符で囲むと引用符ごと入る)ため、文字列の "1.0" を固定値で出せなかった。
  - **値の変換器は `io.debezium.converters.BinaryDataConverter`**(バイト列はそのまま、それ以外は `JsonConverter` に委ねる)。heartbeat のレコード(値は Struct)を Kafka に実際に送るため。`ByteArrayConverter` では heartbeat を送れず、`Filter` で落とすと、Debezium は落としたレコードのオフセットで LSN を確定させない(Kafka Connect が確認済みとして扱わない)ため、注文のない間にスロットが進まなかった(統合テストで確かめた)。heartbeat は内部用のトピック `__debezium-heartbeat.order-outbox` に出る。
  - **heartbeat**: `heartbeat.interval.ms=10000`、`heartbeat.action.query=SELECT pg_logical_emit_message(true, 'eiaf-heartbeat', now()::text)`。ほかの DB の WAL だけが増える間も、order_service の DB にトランザクションを作り、その確定の位置で LSN を進める。**トランザクションの外のメッセージ(第 1 引数 false)では確定の位置が進まなかった。** この関数は追加の権限なしで debezium のロールから呼べるので、debezium の権限は REPLICATION と Outbox の表の SELECT だけのまま。論理デコーディングのメッセージのイベント(`order-outbox.message`)は `Filter`(述語 `TopicNameMatches`)で落とす。
  - そのほか: `snapshot.mode=no_data`(表は常に空)、`tombstones.on.delete=false`、`publication.autocreate.mode=disabled`(publication は `eiaf_outbox`)、`slot.name=order_outbox`、`extended.headers.enabled=false`(Debezium 3 が付ける `__debezium.context.*` のヘッダは契約にない内部の情報なので出さない)、キーは `StringConverter`、出力先のトピックは Connect が作る(`topic.creation.default.partitions=3`)。
  - PostgreSQL に `max_slot_wal_keep_size=1GB` を設けた(コネクタが止まっても WAL がディスクを使い切らない)。上限を超えたときの監視と回復は P06 ④(`docs/runbooks/cdc-outbox-lag.md`)。
