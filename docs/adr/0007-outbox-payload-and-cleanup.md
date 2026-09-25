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
- Debezium の設定(キー名は P06 で採用する Debezium のバージョンのドキュメントで確認して固定する):
  - `transforms=outbox`(`io.debezium.transforms.outbox.EventRouter`)
  - `value.converter=org.apache.kafka.connect.converters.ByteArrayConverter`(アプリで Avro にしたバイト列をそのまま流す)
  - `route.by.field=topic`、`route.topic.replacement=${routedByValue}` で、`topic` 列の値(`{domain}.{entity}.{event}.v{n}`)をそのままトピック名にする
  - `table.field.event.key=aggregate_id`(パーティションキー)
  - `table.fields.additional.placement` で `traceparent`, `correlation_id`, `ce_*` を Kafka ヘッダに載せる(CloudEvents binary mode)

### 2. クリーンアップ
- **既定は「挿入直後に削除」パターン**: 業務更新・outbox への INSERT・同じ行の DELETE を、同一のローカルトランザクションで行う。Debezium は WAL から INSERT を読み取って発行し、DELETE イベントは Event Router が発行しない(Event Router の既定動作。コネクタ側も `tombstones.on.delete=false` にして tombstone を出さない)。テーブルには行が残らないため、肥大化も掃除用のジョブも発生しない。
  - 発行の確認は、Debezium の replication slot が WAL の位置を進めることで担保される。slot が進まない間は WAL が保持されるので、欠損は起きない。
  - 監視: replication slot の遅延(`pg_replication_slots` の `confirmed_flush_lsn` の差)と Connect のタスク状態を Prometheus で監視し、閾値超過をアラートにする(Runbook: `docs/runbooks/cdc-outbox-lag.md`)。
- **調査・監査のために行を残す必要があるサービスだけ**、次の保持期間パターンを使う(サービスの ADR か設定で選択):
  - `created_at` で日次の RANGE パーティションにし、保持期間(既定 7 日。Kafka 側の保持期間と揃える)を過ぎたパーティションを `DETACH` → `DROP` するジョブを `platform/outbox` で提供する。
  - DROP する前に、該当期間の slot 位置が進んでいることを確認し、進んでいなければ DROP を中止してアラートを出す。
- 監査の証跡は Outbox ではなく Audit(ADR-0008)に残す。

## Alternatives Considered
- JSONB で保存し、Connect 側で Avro に変換する: スキーマ検証がアプリの外に出てしまい、契約違反が発行時まで見つからない。Connect の変換設定にスキーマの知識が漏れる。不採用。
- Avro のバイト列ではなく Avro の JSON エンコードで保存する: 人が読みやすいが、Connect 側での再エンコードが必要になり、上と同じ問題がある。不採用。
- 定期バッチで発行済みの行を削除する(発行済みフラグ方式): 発行済みかどうかを Debezium から知る手段がなく、アプリ側でフラグを管理すると二重書き込みと同じ問題が生じる。不採用。
- アプリがポーリングして発行する(Transactional Outbox + Poller): Framework 8.1 のログベース CDC を既定とする方針に反する。不採用。

## Consequences(トレードオフ)
- テーブルに行が残らないため、「何が発行されたか」の確認は Kafka とトレースで行う。行を残したい場合は、保持期間パターンを選ぶ。
- アプリが Apicurio に依存する(`platform/outbox` に閉じ込める)。Registry の停止中は業務更新も失敗するので、スキーマ ID をキャッシュして緩和する。
- ByteArrayConverter を使うため、Connect の SMT でペイロードの中身を加工することはできない(加工しないことを原則とする)。
