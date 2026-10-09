# ADR-0006: Command / Queue チャネルを Kafka のコマンドトピックで実装する
- Status: Accepted
- Date: 2026-09-25
- Framework 参照章: 2.2(Message Channel), 4.2, 6.2, 13

## Context
Framework は「特定の 1 系に処理させたい」指示を Queue で送ると定めている(4.2・6.2・6.7)。
P07 の注文 Saga(Orchestration 方式)は、order から inventory / payment / shipping へのコマンド(引当・決済・出荷・各補償)を必要とする。
ADR-0003 のミドルウェア構成には Queue 専用の基盤がない。

## Decision
- コマンドは Kafka のトピックで送る。命名は `{domain}.{entity}.cmd-{command}.v{n}`(例: `inventory.stock.cmd-reserve.v1`、`payment.payment.cmd-refund.v1`)。
  - Topic 命名の 4 セグメント構成を保ち、naming lint は `{event}` セグメントの `cmd-` prefix でコマンドを判別する。
- 各コマンドトピックの Consumer Group は **受信サービスの 1 つだけ**(`{service}.command`)とする。他サービスからの購読は contract-check と catalog の検査で拒否する。
- 形式・ヘッダ・互換性・冪等消費(processed_message)・DLQ(`{topic}.dlq`)・Replay は Event と同じ標準に従う。
- パーティションキーは Saga ID とし、同じ Saga のコマンドの順序を保証する。
- catalog では `style: event`、`pattern: queue` として登録する。
- コマンドの結果は、受信側が Domain Event(例: `inventory.stock.reserved.v1`)として発行し、Orchestrator がそれを購読する。

## Alternatives Considered
- **RabbitMQ などの Queue 専用ミドルウェアを追加する**: Queue としての意味は正確だが、ローカルのメモリ負荷と運用対象が増え、DLQ・監視・スキーマ管理を 2 系統持つことになる。不採用。
- **Kafka Share Group(KIP-932 / Queues for Kafka)**:
  - 成熟度: Apache Kafka 4.0 で Early Access、4.1 で Preview、**4.2(2026-02)で production-ready**。4.3(2026-05)で share group の設定が追加された(KIP-1240)。**Share Group 用の DLQ(KIP-1191)は 4.4 以降の予定**で、`share.version=2` の feature flag を有効にする必要がある。本 ADR の時点での最新安定版は 4.3.1(4.4.0 はリリース計画上 2026-09-09 以降で、未確認)。Kafka のバージョンは P03 で `libs.versions.toml` と docker compose に固定する。
  - 不採用の理由:
    1. 本リポジトリの DLQ 標準(`{topic}.dlq` とエラーメタデータのヘッダ)は、Consumer 側で実装して全チャネル共通にしている。Share Group の DLQ(KIP-1191)は broker 側の仕組みで、4.3 ではまだ使えない。
    2. Saga コマンドは Saga ID 単位の順序を必要とする。Share Group はパーティション内の順序を保証しない。
    3. 本実装のコマンド量は、パーティション数を超える並列度を必要としない。
    4. Debezium・Apicurio serde・Testcontainers・Toxiproxy の検証は、通常の Consumer Group で揃えるほうが負担が小さい。
  - 将来移行する条件(すべてを満たしたら再評価する):
    1. KIP-1191(Share Group の DLQ)が採用中の Kafka の安定版で GA になっている。
    2. 順序が不要で、パーティション数を超える並列処理が必要なワークキュー(例: 非同期推論ジョブ、Framework 17.4)が現れた。
    3. share consumer の lag・delivery count のメトリクスを OTel / Prometheus で監視できる。
    4. `platform/messaging-kafka` の冪等消費と DLQ の API を変えずに、実装だけを差し替えられる。
  - 移行する場合は本 ADR を Superseded にして新しい ADR を起票し、対象トピックの catalog の `pattern` を更新する。

## Consequences(トレードオフ)
- Kafka は読んでもメッセージが消えないため、処理済みかどうかは processed_message テーブルで判定する(At-Least-Once + 冪等)。
- 並列度はパーティション数が上限になる。
- コマンドトピックとイベントトピックが同じ命名空間に並ぶため、`cmd-` prefix の lint が必須になる。

## 改訂履歴
- 2026-09-26: コマンドトピックのカタログでは、受信側を provider、送信側を senders とする(ADR-0013)
- 2026-09-26: P03 で Kafka を **4.3.1** に固定したため(ADR-0016)、Share Group の採否を再評価した。**結論: 不採用のまま**(本 ADR の決定を維持する)。
  - 4.3.1 で DLQ は使えない: 4.3.1 の `TopicConfig.java` には Share Group の DLQ(KIP-1191)の設定がなく、trunk にだけ `errors.deadletterqueue.group.enable`(「share group の dead-letter queue として使えるようにする」)がある。つまり KIP-1191 は 4.4 以降になる。4.4.0 は 2026-09-26 時点で RC(Docker Hub に `4.4.0-rc2`)で、安定版は出ていない。
  - 移行条件の判定:
    1. KIP-1191 が採用中の安定版で GA: **満たさない**(上記)。
    2. 順序が不要で、パーティション数を超える並列処理が必要なワークキュー: **満たさない**(現在の連携にない。非同期推論ジョブなどは未計画)。
    3. share consumer の lag・delivery count を OTel / Prometheus で監視できる: **未評価**(1・2 を満たさないため評価していない)。
    4. `platform/messaging-kafka` の API を変えずに差し替えられる: **未評価**(同上。API は P06・P07 で作る)。
  - 参考: Share Group は 4.2 から production-ready で、4.3.1 には Share Group の経路のデッドロックの修正(KAFKA-20505)が入っている。単一ブローカーで Share Group を使う場合は `share.coordinator.state.topic.replication.factor` と `min.isr` を 1 にする必要があり、ローカル基盤では設定済み(`infra/local/docker-compose.yml`)。
  - 次の再評価: Kafka を 4.4 系の安定版に上げるとき。
- 2026-10-09: P07 ③ で、注文 Saga のコマンドのトピックを決めた: `inventory.stock.cmd-reserve.v1`・`cmd-release.v1`、`payment.payment.cmd-authorize.v1`・`cmd-void.v1`、`shipping.shipment.cmd-arrange.v1`・`cmd-cancel.v1`(INT-INVENTORY/PAYMENT/SHIPPING-001)。結果は各サービスの Domain Event(-002)で、Orchestrator(order-service の `order.saga`)が購読する。キーは Saga ID。Saga の決定は ADR-0029。
