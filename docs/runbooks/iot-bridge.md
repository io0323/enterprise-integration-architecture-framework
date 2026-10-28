# Runbook: IoT のテレメトリの橋渡し(iot-bridge)

## 対象
- デバイスが MQTT Broker(Mosquitto。IoT の境界)に送るテレメトリ(INT-IOT-001)を、iot-bridge が 1 つの Avro の形にそろえて
  `iot.telemetry.reported.v1`(INT-IOT-002。保持 7 日)に出す連携(P11。ADR-0039)。
  - iot-bridge は永続セッション(client id `iot-bridge`・セッションの期限 24 時間)で購読し、Kafka に書けてから PUBACK を返す。
    止まっている間は、Mosquitto がメッセージを溜める。
  - 受け付けないもの(知らない形式の版・形式の誤り・トピックの誤り)は `iot.telemetry.reported.v1.dlq` に、受け取った JSON のまま隔離する。
- ローカルの起動: `make up PROFILE=iot`。デバイスの一覧は `infra/local/mosquitto/devices.txt`、パスワードは `infra/local/secrets/mqtt/device-passwords`
  (`make env` が作る。値を画面やチケットに貼らない)。
- **溜め込みの上限**(`infra/local/mosquitto/mosquitto.conf`。ADR-0039 §9): `max_queued_messages 300000`。
  前提の流入(10,000 台 × 1 件/分)で、**iot-bridge が約 29 分止まると上限に達する**。上限を超えると、Broker は**新しいメッセージを黙って捨てる**
  (先に溜めた古いものは残る。デバイスには PUBACK が返っているので、デバイスは送り直さない。取り戻せない。実際の Mosquitto で確かめた: `MosquittoBrokerIT`)。

| アラート | 重大度 | 見る節 |
|---|---|---|
| `MqttBridgeQueueHalfFull` / `MqttBridgeQueueNearlyFull` | warning / critical | [溜め込みが増えているとき](#溜め込みが増えているとき) |
| `MqttMessagesDropped` | critical | [捨てたとき](#捨てたとき) |
| `IotBridgeDown` | critical | [iot-bridge が止まっているとき](#iot-bridge-が止まっているとき) |
| `IotBridgeRetrying` | warning | [Kafka に送れないとき](#kafka-に送れないとき) |
| `MosquittoMetricsMissing` | warning | [溜め込みの数が見えないとき](#溜め込みの数が見えないとき) |
| `EventDeadLetters`(`topic="iot.telemetry.reported.v1.dlq"`) | warning | [DLQ に隔離されたとき](#dlq-に隔離されたとき) |

## まず見るもの
- ダッシュボード **IoT — Telemetry bridge**(Grafana のフォルダ EIAF): 溜め込みの数と上限・捨てた数・形式の版ごとの発行・DLQ の理由・
  時計の疑わしいもの・遅れ・送り直し。
- ログ: `make logs SERVICE=iot-bridge` / `SERVICE=mosquitto`。ペイロードの値は出ない(トピック・理由・コードだけ)。
- 準備の状態: iot-bridge の `/health/ready`(スキーマ ID・書くトピック・MQTT の購読・直近の処理が一時的な失敗でないこと)。
  コンテナの外には公開していないので、`make ps` の health で見る。

## 溜め込みが増えているとき
`MqttBridgeQueueHalfFull`(上限の半分。前提の流入で約 15 分止まっている)・`MqttBridgeQueueNearlyFull`(8 割。このままでは捨て始める)。
1. iot-bridge が動いているか(`make ps`。`IotBridgeDown` が出ていれば[その節](#iot-bridge-が止まっているとき))。
2. 動いているのに溜まるなら、Kafka に送れていない([Kafka に送れないとき](#kafka-に送れないとき))か、流入が処理の速さを超えている
   (ダッシュボードの「発行したテレメトリ」が平らなのに溜め込みが増える)。
3. 戻すと、溜めていたものが古い順に届く(デバイスごとの順序は保つ)。溜め込みの数が下がり始めることを確かめる。
4. 上限に届きそうで、すぐに戻せないとき: 上限を上げると Broker のメモリが要る(Mosquitto はキューをメモリに持つ。`mem_limit` も合わせる)。
   値を変えたら、`iot.rules.yml` のしきい値・`iot.test.yml`・ADR-0039 §9 の「耐えられる時間」を同じ PR で直す。

## 捨てたとき
`MqttMessagesDropped`。溜め込みが上限(300000 件か 256 MiB)を超え、Broker が新しいメッセージを捨てた。**そのデータは失われている**。
1. 捨て始めた時刻と件数を記録する(ダッシュボードの「捨てた数」。Prometheus: `mosquitto_publish_messages_dropped` は Broker の起動からの累計で、
   1 件目を捨てたときに系列が現れる)。欠けるのは**上限に達した後に届いた分**(上限に達した時刻から、iot-bridge が戻って溜め込みが減り始めるまで)。
   デバイスの中の保持は範囲外(ADR-0039 §1)なので、Broker からは取り戻せない。消費者(分析など)に、欠けた時間帯を知らせる。
2. 原因(iot-bridge の停止・Kafka の停止)を[溜め込みが増えているとき](#溜め込みが増えているとき)の手順で戻す。
3. 同じことが繰り返されるなら、上限と流入の見積もり(ADR-0039 §9)を見直す。

## iot-bridge が止まっているとき
`IotBridgeDown`(Mosquitto は動いているのに、iot-bridge のメトリクスが 2 分ない)。止まっている間は Mosquitto に溜まる。
1. `make ps` と `make logs SERVICE=iot-bridge`。起動で止まるなら、多くは準備の条件(スキーマ ID の解決・書くトピック・MQTT の接続)。
   - スキーマが登録されていない: `make schemas`(または `schema-publish` のコンテナ)。
   - トピックがない: `kafka-topics` のコンテナ(`infra/local/kafka/topics.conf`)。
   - MQTT に接続できない: Mosquitto の health と、`.env` の `MQTT_BRIDGE_PASSWORD`。
2. 戻すときは client id(`IOT_BRIDGE_MQTT_CLIENT_ID`。既定 `iot-bridge`)を**変えない**。変えると、Broker の溜め込み(前のセッション)を引き継がない。

## Kafka に送れないとき
`IotBridgeRetrying`(Kafka に送れず、PUBACK を返さずに送り直し続けている)。iot-bridge は 1 件ずつ順に処理するので、その間は次に進まず、
受け取りきれない分は Mosquitto に溜まる(背圧。ADR-0039 §8)。
1. Kafka と Schema Registry の状態(`make ps`)。ログの `code=` で原因を見る(`publish_failed` など)。
2. Kafka が戻れば、送り直しが成功して自然に進む(待ち時間は最大 30 秒)。溜め込みの数が下がることを確かめる。

## 溜め込みの数が見えないとき
`MosquittoMetricsMissing`(エクスポーター(Telegraf。`mosquitto-exporter`)は動いているのに、`$SYS` の接続の数が届かない)。
上限に近づいても気づけない状態なので、早く戻す。溜め込みの数・捨てた数は 0 のうちは `$SYS` に出ないので、それらがないだけなら正常。
1. Mosquitto が動いているか(`make ps`)。
2. Telegraf のログ(`make logs SERVICE=mosquitto-exporter`)。監視のユーザー(`eiaf-monitor`。`$SYS` の読み取りだけ)で接続・購読できているか。
   パスワードは `.env` の `MQTT_MONITOR_PASSWORD`(Mosquitto は起動のたびにパスワードと ACL を作り直すので、`.env` を変えたら Mosquitto も作り直す)。

## DLQ に隔離されたとき
`EventDeadLetters` の `iot.telemetry.reported.v1.dlq`。理由はダッシュボードの「DLQ に隔離した数」(`eiaf.dlq.reason`)。
- `UNSUPPORTED_PAYLOAD_VERSION`: 知らない形式の版(新しいファームウェアの版を、まだデコーダが受け付けていない・やめた版が届いた)。
  契約(`contracts/devices`)とデコーダに版を足して出し直した後に、処理し直す。
- `INVALID_PAYLOAD`・`INVALID_TOPIC`: デバイスの誤り。デバイスの持ち主(team-iot)に知らせる。
- 処理し直し(デコードからやり直す。`make dlq-replay` は使わない。値が Avro ではないため):
  ```bash
  make iot-reprocess-dlq ARGS="--limit 10"            # dry-run(何を処理し直すかを表示するだけ)
  make iot-reprocess-dlq ARGS="--limit 10 --execute"  # 処理し直す(同じ ce_id・受け取った時刻のまま出す)
  ```
  引数・条件・終了コードは `docs/runbooks/event-dlq-replay.md` と同じ。まだ受け付けないものは `REJECTED` になり、DLQ に残る。

## デバイスを足す・パスワードを作り直す
1. `infra/local/mosquitto/devices.txt` に `{tenant}/{deviceId}` の行を足す。
2. `make env`(足したデバイスのパスワードを `secrets/mqtt/device-passwords` に追記する)。
3. `make up PROFILE=iot`(Mosquitto が起動のたびに、一覧からパスワードと ACL を作り直す)。
- パスワードを作り直すときは、`secrets/mqtt/device-passwords` のその行を消して 2・3 を行う。
- デバイスは自分のトピック(`devices/{tenant}/{deviceId}/...`)にだけ書ける。本番はデバイスごとの証明書の mTLS(#29)。

## 古い形式の版をやめる
ADR-0039 §4 の手順。ダッシュボードの「発行したテレメトリ」で、その版の受信が、デバイスが通信できない最長の期間を超えて(既定 90 日)ゼロであることを
確かめてから、team-iot の承認を Issue に残し、契約・デコーダから外す(contract-check の CC-COMPAT-004 の例外が要る)。
やめた後に届いたものは DLQ に入る(失わない)。
