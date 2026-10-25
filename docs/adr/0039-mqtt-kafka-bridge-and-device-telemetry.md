# ADR-0039: MQTT から Kafka への橋渡しと、デバイスのテレメトリの前提
- Status: Accepted
- Date: 2026-10-23
- Framework 参照章: 2.3, 6, 12, 13, 14, 17.3

## Context
P11(IoT (MQTT) & KMP SDK。#14)では、デバイスのテレメトリを MQTT Broker(Mosquitto)で受け、Kafka の `iot.telemetry.reported.v1` に橋渡しする。Framework 17.3 は「デバイス側の制約(軽量・不安定回線)は MQTT で吸収し、Broker Bridge 以降は 6 章の企業標準(スキーマ・DLQ・監視)に載せる」とする。

業務サービスとは違う、デバイスならではの前提がある。
- **ファームウェアはすぐに更新できない**。何年も前のファームウェアのデバイスが混在し、古い形式のメッセージが長く届き続ける。
- **デバイスの時計はずれる**(時計が合っていない・電池が切れて初期化された)。
- **通信が切れる**。切れていた間にデバイスが溜めたデータが、つながったときにまとめて遅れて届く。
- **QoS 1 は At-Least-Once**。同じメッセージが重ねて届く。デバイスは再起動・初期化でメッセージの番号を振り直すことがある。

## Decision
### 1. 範囲と境界
- 境界は **MQTT Broker** に置く(Framework 2.3)。デバイスとゲートウェイの間の通信(無線方式・電力・デバイスの管理・FOTA)と、デバイスの中でのデータの保持は範囲外(IoT 通信設計の責務)。この Framework が決めるのは、Broker で受け取る形(契約 `contracts/asyncapi/device-telemetry.v1.yaml`・`contracts/devices`。INT-IOT-001)と、Broker から先(INT-IOT-002)。
- 構成:
  ```
  デバイス ──MQTT v5 QoS1(デバイスごとの資格情報・ACL)──▶ Mosquitto ──永続セッション──▶ iot-bridge ──▶ iot.telemetry.reported.v1
     devices/{tenant}/{deviceId}/telemetry                                                    └─(形式の誤り・知らない版)─▶ .dlq
  ```
- デバイスの側の部品(シミュレータ・SDK)は `shared/integration-sdk` の MQTT の部分を使う。SDK は HTTP の部分(`shared/integration-sdk/http`)と MQTT の部分(`shared/integration-sdk/mqtt`)の 2 つのモジュールに分け、`tools/device-simulator` は MQTT の部分だけに依存する(HTTP の Curl のエンジン(libcurl)を native のバイナリに巻き込まない。MODULE_DESIGN)。SDK と MQTT のクライアントは ADR-0037・ADR-0038 で決める。

### 2. 橋渡しの方式: 自前の Kotlin のサービス(`services/iot-bridge`)
| 方式 | ライセンス | MQTT v5 のユーザープロパティ(traceparent) | PUBACK と Kafka の書き込みの順 | Avro・Schema Registry・DLQ・形式の版 |
|---|---|---|---|---|
| **自前の Kotlin のサービス(採用)** | 自社のコード(依存は Apache-2.0 など) | 読める(クライアントの選定は ADR-0038) | Kafka の確認の後に PUBACK(手動の ack) | `platform/messaging-kafka`・`platform/schema-registry`・DLQ の標準をそのまま使う。版ごとのデコーダを持てる |
| Lenses stream-reactor の MQTT のソースのコネクタ(Kafka Connect) | Apache-2.0 | 読めない(Eclipse Paho の `mqttv3` のクライアントを使う。`MqttManager.scala` で確認) | 受け取ったメッセージをメモリのキューに入れ、Kafka に書く前に PUBACK が返る(コネクタが落ちると失う) | KCQL と変換器。版ごとの変換・DLQ の標準は自前で足す必要がある |
| Confluent の MQTT Source Connector | プロプライエタリ(30 日の試用の後はサブスクリプション。開発用のライセンスは 1 台のブローカーだけ) | — | — | 不採用(Cloud Agnostic・OSS の方針に合わない) |

- 自前のサービスにする理由: traceparent をユーザープロパティから引き継ぐ(DoD)・Kafka の確認の後に PUBACK を返す(At-Least-Once を崩さない)・形式の版ごとのデコーダ・社内の標準(Avro・CloudEvents・DLQ)を、既存の platform の部品で満たせる。コネクタで満たすには、結局コネクタの改造か前段の変換が要る。
- iot-bridge は DB を持たず、Kafka に直接書く。DB への書き込みとイベントの発行の二重書込みがないので、Outbox は使わない(CLAUDE.md §5 の「DB→Event」の対象外)。
- 4 モジュール(domain・application・adapters・app)。domain に形式の版ごとの読み替え・時刻の判定(§7)、adapters に MQTT の購読・Kafka への発行・DLQ。
- ブリッジの MQTT のクライアントには、**購読・MQTT v5 のユーザープロパティ・手動の ack** が要る(ADR-0038 で選ぶ)。

### 3. デバイスの資格情報と ACL
- デバイスごとにユーザーを作る。ユーザー名は `{tenant}/{deviceId}`。共有の資格情報は使わない(1 台の漏洩で全台のトピックに書けてしまう)。
- ACL は既定で拒否し、デバイスには `devices/{tenant}/{deviceId}/#` への**書き込みだけ**を許す。他のデバイスのトピックには書けない(MQTT v5 の PUBACK の理由のコード 0x87 Not authorized)。
  ACL はデバイスごとに 1 行ずつ作る(`pattern` の `%u` の置き換えは使わない。ユーザー名に `/` を含むため、置き換えの挙動に頼らない)。
- iot-bridge は専用のユーザーで、`devices/+/+/telemetry` の**読み取りだけ**。メトリクスの取得(§9)の専用のユーザーは `$SYS/#` の読み取りだけ。
- iot-bridge は、デバイスの ID を **ACL で守られたトピック**から取る。ペイロードの中の値は信じない(ペイロードにはデバイスの ID を入れない)。
- **本番はデバイスごとの証明書(mTLS)で認証する**。証明書の主体(CN)を `{tenant}/{deviceId}` にし、Mosquitto の `use_identity_as_username` で同じ ACL を当てる。証明書の発行・失効・更新はデバイスの管理(範囲外。§1)。
  **ローカルはパスワードのファイル**(`make env` が作ったデバイスの一覧から、起動のたびにパスワードのファイルと ACL のファイルを作り直す。値は `infra/local/secrets/` で git の対象外。ADR-0016)で、平文(ADR-0008)。Broker の TLS 化とデバイスの証明書の認証は #29(P14)で行う。

### 4. ペイロードの形式の版
- ペイロードは JSON で、`v` が形式の版。トピックには版を入れない(古いファームウェアはトピックを変えられない。ACL を 1 つの形に保つ)。形式は `contracts/devices/{message}.v{n}.schema.json` に置き、契約の messages に並べた版をすべて受け付ける(最初は版 1(温度と湿度の決まった 2 つ)と版 2(名前・値・単位の一覧))。
- **出した版は変えない**(CC-COMPAT-004。説明の文だけは直してよい)。変更は新しい版で足す。未知の項目は無視する(Tolerant Reader。版の中でファームウェアが項目を足しても受け付ける)。
- iot-bridge は、どの版も 1 つの Avro の形(`TelemetryReported`)にそろえる。**デバイスの形式の版と Kafka のスキーマの版は切り離す**:
  - Kafka の側は FULL_TRANSITIVE(ADR-0014)。新しい版が新しい情報を運ぶときは、Avro に default 付きの項目を足す。古い版のデバイスからのメッセージはその項目が default(null)になる。消費者はどの版のデバイスかを意識しない。
  - デバイスの版をやめても、Avro のスキーマは変えない(項目は default のまま残る)。FULL_TRANSITIVE で表せない変更が要るときだけ、新しいトピック(`.v2`)を作って並行して出す(ADR-0014)。
- **知らない版・形式の誤りは捨てない**。`iot.telemetry.reported.v1.dlq` に隔離する(`eiaf.dlq.reason` は `UNSUPPORTED_PAYLOAD_VERSION`・`INVALID_PAYLOAD`。値は受け取った MQTT のバイト列のまま、キーは `{tenant}/{deviceId}`、MQTT のトピックをヘッダ `eiaf.mqtt.topic` に付ける)。
  この DLQ の値は Avro ではないので、`tools/dlq-replay` では戻さない(元のトピックに JSON を書いてしまう。`make dlq-replay` も拒否する)。デコーダを直した後に、**iot-bridge の再処理のコマンド**で DLQ を読み直して出し直す(⑤。非同期の 3 点セットの Replay)。
- **版をやめる手順**:
  1. 版ごとの受信数(`eia.iot.telemetry.received` の `payload_version`)が、デバイスが通信できない最長の期間を超えて(既定 90 日)ゼロであることを確かめる。
  2. デバイスの持ち主(team-iot)の承認を Issue に残す。
  3. 契約の messages から外し、形式のスキーマのファイルとデコーダを消す。active の連携では CC-COMPAT-004 の違反になるので、承認者と 14 日以内の期限の例外(ADR-0013)で許可する。
  4. やめた後に届いたものは DLQ に入る(失わない)。必要ならデコーダを戻して再処理する。

### 5. 重複と ce_id
- デバイスは、ペイロードに **`boot`(起動のたびに乱数で作るセッション ID。64 ビット以上)** と **`seq`(起動ごとに 0 から数える番号。QoS 1 の再送では同じ値)** を入れる。`seq` が上限に達したら `boot` を作り直す。
  - 起動の回数ではなく乱数のセッション ID にする: 起動の回数は書き換えのできる記憶が要り、初期化・部品の交換で 0 に戻ると、以前と同じ組が再び現れる。64 ビットの乱数なら、同じデバイスで重なる確率は無視できる(ファームウェアはハードウェアの乱数で作る)。
- **ce_id** は `{tenant}/{deviceId}/{boot}/{seq}` から作る UUID の版 5(名前空間 `692dfaa8-f3ab-483e-b518-69e94388a16b`。SHA-1 は識別子を作るためだけに使う)。同じメッセージは何度届いても同じ ce_id になる。**番号が振り直されても `boot` が違うので、以前のメッセージと ce_id が重ならず、正当な新しいデータを重複として捨てない**(⑤ の統合テストで確かめる)。
- iot-bridge は状態を持たない。PUBACK は Kafka の確認の後に返すので、「Kafka に書いた後・PUBACK の前」に落ちると、Broker が送り直して Kafka に同じ ce_id のメッセージが 2 回入る。Producer は冪等(`enable.idempotence`)にして、Producer の再試行による重複は防ぐ。
- 重複を取り除くのは**消費者**(ce_id で `platform/inbox`。ADR-0028)。inbox の保持期間(14 日)は、重複が届きうる期間(ブリッジのセッションの期限 24 時間(§9)・DLQ の保持期間 7 日)より長い。
- 同じ `boot`・`seq` で中身が違うメッセージ(ファームウェアの誤り)は、iot-bridge では見分けない(消費者は先に届いた方を使う)。

### 6. パーティションのキーと順序
- キーは `{tenant}/{deviceId}`(デバイスの ID はテナントの中でだけ一意)。デバイスごとに、Kafka には届いた順に並ぶ。
- iot-bridge は 1 台で購読する。MQTT の共有サブスクリプション(`$share/...`)で台数を増やすと、同じデバイスのメッセージが別のインスタンスに分かれて順序が崩れる。台数を増やすときは、トピックの範囲(テナントごとなど)で購読を分ける(必要になったら ADR を改訂する)。

### 7. 時刻(デバイスの時計と受け取った時刻)
- 両方を持つ。`deviceTime`(デバイスの申告。版 1 の `ts`(秒)・版 2 の `time`(ミリ秒))と `receivedAt`(iot-bridge が MQTT から受け取った時刻)。
- **`receivedAt` を使う**: `ce_time`・Kafka のレコードのタイムスタンプ(保持期間の起点。`deviceTime` にすると、遅れて届いたデータがすぐに保持期間を過ぎて消える)・遅れの監視・運用の判断。
- **`deviceTime` を使う**: 分析での発生の時刻(Stream Processing の側。Framework 2.3)。
- `clockSuspect`: `deviceTime` がない、`receivedAt` より 5 分を超えて未来、または 2020-01-01T00:00Z より前(時計が初期化されたデバイスの典型の値)なら true。**値は直さない**(直し方は使う側が決める)。過去にずれているだけ(遅れて届いた)では疑わない(§8)。

### 8. 通信が切れていた間のデータ(遅れて届く・まとめて届く)
- 古さによらず受け付ける(捨てない)。Kafka には届いた順(`receivedAt` の順)に並び、`deviceTime` の順とは限らない。消費者は `deviceTime` が単調に増えると仮定しない(AsyncAPI の説明に書いた)。
- まとめて届いたときの背圧: iot-bridge は MQTT v5 の Receive Maximum で同時に受け取る数を絞り、Kafka の確認の後に PUBACK を返す。受け取りきれない分は Broker のブリッジのセッションのキューに溜まる(§9 の上限の対象)。
- 遅れの大きさ(`receivedAt - deviceTime`)をヒストグラムで出す。ラベルはテナントと形式の版だけ(デバイスの ID はラベルにしない。数が多すぎる)。`clockSuspect` の数も出す。

### 9. Broker の溜め込みの上限と、耐えられるブリッジの停止の時間
- iot-bridge は永続セッション(clean start = false、セッションの期限 24 時間)で購読する。iot-bridge が止まっている間、Mosquitto はブリッジのセッションのキューにメッセージを溜める。デバイスは購読しないので、溜め込みはブリッジのセッションだけにある。
- 設計の前提の流入: 10,000 台 × 1 件/分 ≈ 170 件/秒(1 件はヘッダとユーザープロパティを含めて 約 500 バイト)。
- 上限(`mosquitto.conf`。⑥):
  | 設定 | 値 | 意味 |
  |---|---|---|
  | `max_queued_messages` | 300000 | ブリッジのセッションに溜める数の上限(先に効く) |
  | `max_queued_bytes` | 268435456(256 MiB) | 大きなメッセージでメモリを使い切らないための上限(前提の流入では 300000 件 ≈ 150 MB で、数の上限が先に効く) |
  | `max_inflight_messages` | 100 | 送り中(PUBACK の待ち)の数 |
  | `max_packet_size` | 16384 | 1 件の上限(版 2 の測定値は 32 件まで) |
  | `retain_available` | false | retain を使わない(`$SYS` の保存の数を、溜め込みの数として読めるように) |
  | `persistent_client_expiration` | 2d | セッションの期限を付けない古いクライアントの後片付け |
- **耐えられるブリッジの停止の時間 = `max_queued_messages` ÷ 流入の速さ**。前提の流入で **約 29 分**。デバイスの数・送る間隔が変わったら、この値を見直す(上限を上げると Broker のメモリが要る。Mosquitto はキューをメモリに持つ)。
- **上限を超えたときの挙動**: Broker は、そのセッションの新しいメッセージを**黙って捨てる**(`max_queued_bytes` は mosquitto.conf(5) に明記。`max_queued_messages` も同じ扱いであることを ⑥ の検証で確かめる)。デバイスには PUBACK が返っているので、デバイスは送り直さない。**そのデータは失われる**(Broker からは取り戻せない。デバイスの中の保持は範囲外。§1)。捨てた数は `$SYS/broker/publish/messages/dropped` で数える。
- **メトリクスとアラート**(⑥): Mosquitto の `$SYS` をエクスポーター(候補は sapcc/mosquitto-exporter(Apache-2.0)。イメージと版は ⑥ で確かめて `images.env` に固定する。合わなければ同じ役割の別のものを ADR の改訂で決める)で Prometheus に出す。iot-bridge 自身からは出さない(iot-bridge が止まっているときこそ要る)。
  | アラート | 条件 | 重さ |
  |---|---|---|
  | 溜め込みが半分 | 保存のメッセージの数(`$SYS/broker/store/messages/count`)が上限の 50% を超えた(前提の流入で停止 約 15 分) | warning |
  | 溜め込みが上限に近い | 80% を超えた | critical |
  | 捨てた | 捨てた数が増えた | critical |
  | ブリッジが止まった | iot-bridge のメトリクスが 2 分取れない | critical |
  - `$SYS` の保存の数は Broker 全体の数(送り中・溜め込み・retain)。retain を使わず(上の `retain_available`)、購読するのはブリッジだけなので、ブリッジのセッションの溜め込みとして読める。
  - 対応の手順は Runbook(`docs/runbooks/iot-bridge.md`。⑥)に書く。捨てた場合は、時間帯と件数を記録する(取り戻せない)。

### 10. トレース
- デバイスは MQTT v5 のユーザープロパティで `traceparent` と `correlationid` を送る。iot-bridge は、その子の CONSUMER のスパンで処理し、PRODUCER のスパンの traceparent を Kafka のヘッダに付ける。`correlationid` は引き継ぐ。
- MQTT v3.1.1 のデバイスはユーザープロパティを送れないので、契約では必須にしない(CC-MQTT-001)。iot-bridge が新しいトレースを始め、`correlationid` を採番する。

## Alternatives Considered
- **Kafka Connect のコネクタ**(§2): Lenses は MQTT v5 のユーザープロパティを読めず、PUBACK が Kafka への書き込みより先に返る。Confluent はライセンスが合わない。不採用。
- **Broker を Kafka への橋渡しを持つものに替える**: P03 で Mosquitto に決めた構成(ADR-0016)を変える。橋渡しの方式の比較とは別の判断になり、ローカルで完結しない製品版の機能に頼る恐れもある。比べない。
- **ce_id をメッセージの番号だけから作る**: 再起動・初期化で番号が振り直されると、正当な新しいデータが以前のものと同じ ce_id になり、消費者が重複として捨てる。不採用(§5)。
- **起動の回数を `boot` にする**: 初期化・部品の交換で回数が戻る。不採用(§5)。
- **ce_id を受け取るたびに作る(UUIDv7)**: QoS 1 の再送やブリッジの送り直しで、同じメッセージが別の ce_id になり、消費者が重複を取り除けない。不採用。
- **iot-bridge で重複を取り除く**: 状態(デバイスごとの最後の番号)を持つと、DB か状態の置き場が要り、台数を増やすときに分け方が難しくなる。消費者の inbox(全社の標準)に任せる。不採用。
- **トピックに形式の版を入れる(`devices/.../telemetry/v2`)**: 古いファームウェアはトピックを変えられず、ACL も版ごとに要る。不採用。
- **溜め込みの数を iot-bridge から出す**: iot-bridge が止まっている間(溜め込みが増える間)に出せない。不採用(§9)。
- **溜め込みの上限をなくす**(`max_queued_messages 0`): Broker のメモリを使い切ると、全部のデバイスの受け付けが止まる。上限を決めて、超える前にアラートで止まったブリッジを戻す。不採用。

## Consequences(トレードオフ)
- 橋渡しのコード(デコーダ・DLQ の再処理)を自分たちで持つ。形式の版が増えるたびにデコーダを足す。
- 古い版は長く(既定で受信がゼロになって 90 日)受け付け続けるので、デコーダが溜まる。やめる手順(§4)で減らす。
- ブリッジの停止が約 29 分を超えると、データを失う。アラート(§9)と Runbook で、それより前に戻す。デバイスの数が増えたら上限と Broker のメモリを見直す。
- 消費者は ce_id で冪等にする必要がある(全社の標準どおり)。`deviceTime` の順に並ぶとは限らないことも、消費者が扱う。

## 改訂履歴
- 2026-10-23: P11 ①(#14)で作成した。
- 2026-10-24: §2 のブリッジの MQTT のクライアントを HiveMQ MQTT Client に決めた(ADR-0038 §3)。デバイスの側は ktor-network での最小限の実装(ADR-0038 §2)。§3 の Mosquitto の拒否の振る舞いを Mosquitto 2.1.2 で確かめた: ACL で書けないトピックの PUBLISH には PUBACK 0x87、パスワードの誤りには CONNACK 0x87(0x86 ではない)を返す(`MosquittoIT`)。
- 2026-10-25: P11 ⑤a で iot-bridge の中核を実装した。§4 の形式のスキーマは契約(contracts/devices)をそのままリソースに含め、受け取ったペイロードを版ごとのスキーマで検証してから読む(契約が唯一の真実。版を足すときは契約・`DevicePayload`・受け付ける版の一覧を同じ PR で足す)。DLQ の `eiaf.dlq.detail` には、項目の位置と破った規則(JSON Schema のキーワード)だけを入れる(値は入れない)。§5 の ce_id の UUID の版 5 は RFC 9562 の既知の値で確かめた。
- 2026-10-25: P11 ⑤b で iot-bridge の adapters を実装した。§2 の手動の ack は HiveMQ MQTT Client の `publishes(..., manualAcknowledgement = true)` で、処理(発行か DLQ)が確定してから PUBACK を返す。Kafka に送れないときは PUBACK を返さず、RetryPolicy の待ち時間(上限 30 秒)の後に同じメッセージを処理し直す(届いた順に 1 件ずつ。§6)。前のインスタンスの購読で溜まったものは、新しいクライアントからは購読していないものに見えるので、すべての PUBLISH を受け取る(ACL で `devices/+/+/telemetry` しか読めない)。§4 の DLQ は `DeadLetterPublisher.sendExternal`(Kafka 以外から受け取ったものを隔離する)で、恒久的な発行の失敗は理由 `UNPUBLISHABLE`。§5・§7 の ce_id と ce_time・レコードの時刻は `EventProducer` の `EventIdentity` で渡す。メトリクスは `eia.iot.telemetry.received{payload_version}`・`rejected{reason}`・`clock_suspect`・`lag`(秒)・`retries`。番号の振り直しで正当なデータが消費者の inbox で捨てられないこと、止まっている間の溜め込みが戻ると届くことを `IotBridgeIT` で確かめた。
