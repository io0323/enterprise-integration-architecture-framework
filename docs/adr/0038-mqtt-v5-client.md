# ADR-0038: MQTT v5 のクライアント(デバイスの側は ktor-network での最小限の実装、ブリッジの側は HiveMQ MQTT Client)
- Status: Accepted
- Date: 2026-10-24
- Framework 参照章: 6, 13, 14, 17.3

## Context
P11(#14)では、MQTT v5 のクライアントが 2 か所で要る。
- **デバイスの側**(`tools/device-simulator`。Kotlin/Native の linuxX64・macosArm64 の実行ファイル): テレメトリを QoS 1 で送り、traceparent を MQTT v5 のユーザープロパティで運ぶ(ROADMAP の DoD)。ACL で拒否された(PUBACK 0x87)ことを送り手が知れる必要がある(ADR-0039 §3 の確認)。
- **ブリッジの側**(`services/iot-bridge`。JVM): 購読し、ユーザープロパティを読み、**Kafka の確認の後に PUBACK を返す(手動の ack)**(ADR-0039 §2)。

キックオフでは「Kotlin/Native で使える MQTT v5 のクライアントがあるか」をリスクに挙げた。使えるものがなければ、ktor-network で最小限に作る案も比べる。

## Decision
### 1. 候補の比較(2026-10-24 に確かめた)
| 候補 | ライセンス・版 | linuxX64 / macosArm64 | 判断に効いた点 |
|---|---|---|---|
| KMQTT(`io.github.davidepianca98:kmqtt-client`) | MIT・1.0.0(2024-12。Kotlin 2.1.0 でビルド。最後のコミットは 2025-06) | ある | **受け取った QoS 1 の PUBLISH に、アプリの処理の前に PUBACK を返す**(`MQTTClient.kt` の受信の処理で、`publishReceived` を呼ぶ前に PUBACK を送る)ので、ブリッジの手動の ack に使えない。`publish()` は送るだけで PUBACK の理由のコードを返さないので、デバイスの側で ACL の拒否(0x87)を知れない。受信は `step()`・`run()` を回す作りで、coroutines と合わない。native の TLS は OpenSSL をリンクする |
| ktor-mqtt(`de.kempmobil.ktor.mqtt:mqtt-client`) | 独自の表記のライセンス・1.2.0(2026-08) | **linuxX64 の成果物がない**(Maven Central に macosArm64 はあるが linuxX64 はない。JVM・Apple・JS・Wasm) | DoD の linuxX64 のバイナリに使えない |
| mktt(`io.github.nicolasfara:mktt-client`) | Apache-2.0・3.0.0(GitHub のリリース) | — | **Maven Central に成果物がない**(メタデータの版が `unspecified`、jvm・linuxX64・macosArm64 の成果物が 404) |
| Eclipse Paho C を cinterop で使う | EPL-2.0 / EDL-1.0 | ターゲットごとに C のライブラリを組み立てて持つ | クロスビルドとリンクの手間が大きい。Kotlin の API(Result・coroutines)に合わせる層も要る |
| **ktor-network での最小限の実装(デバイスの側に採用)** | 自社のコード(Ktor は Apache-2.0)・Ktor 3.6.0 | ある(ktor-network は jvm・linuxX64・macosArm64 の成果物を持つ) | 必要なパケットだけを実装し、PUBACK の理由のコードを `Result` で返せる。shared/resilience の Retry をそのまま使える |
| HiveMQ MQTT Client(`com.hivemq:hivemq-mqtt-client`。**ブリッジの側に採用**) | Apache-2.0・1.4.1(2026-10-13。活発に保守) | JVM だけ | 購読の手動の ack(`manualAcknowledgement`)・MQTT v5 のユーザープロパティ・永続セッションを持つ |
| Eclipse Paho Java v5(`org.eclipse.paho.mqttv5.client`) | EPL-2.0 / EDL-1.0・1.2.5(**2020-07 から新しい版がない**) | JVM だけ | 保守が止まっている |

### 2. デバイスの側: ktor-network での最小限の実装(`shared/integration-sdk/mqtt`)
- **モジュール**: `shared/integration-sdk/mqtt`(`io.eia.shared.sdk.mqtt`)。ターゲットは jvm・linuxX64・macosArm64(新しい規約 `eia.kmp-socket-library`)。js は持たない(ブラウザからは TCP のソケットを開けない。ktor-network も js を持たない)。HTTP の部分(`shared/integration-sdk/http`)には依存しない(ADR-0039 §1)。commonMain の import は許可の一覧(Konsist の `sharedImportAllowList`)で縛る: Kotlin・coroutines・kotlinx-io・`io.ktor.network`・`io.ktor.utils.io`・kernel・resilience・SDK の MQTT の部分だけ。
- **実装する範囲**: CONNECT / CONNACK・PUBLISH(QoS 0 と 1)/ PUBACK・PINGREQ / PINGRESP・DISCONNECT。プロパティは、送る側は Session Expiry・Content Type(と Payload Format Indicator)・ユーザープロパティ、受ける側は CONNACK の Receive Maximum・Maximum QoS・Maximum Packet Size・Server Keep Alive と Reason String(ほかは型に従って読み飛ばし、知らない識別子は形式の誤り)。
- **実装しない範囲**: SUBSCRIBE(デバイスは受信しない)・QoS 2・AUTH(拡張認証)・Topic Alias・Will・TLS。本番のデバイスの mTLS(ADR-0039 §3)はデバイスのファームウェアの責務で、シミュレータを TLS で動かす必要が出たら #29(P14)で ktor-network-tls(linuxX64 の成果物がある)を足す。
- **Broker の上限に従う**: PUBACK を待つ数は Receive Maximum(と設定の上限)まで。Maximum QoS を超える QoS・Maximum Packet Size を超える大きさは送らずに `MqttInvalidMessage`。Server Keep Alive があればそれで生存確認(PINGREQ)をし、Keep Alive の 1.5 倍の間に何も受け取らなければ切れたとみなす。
- **結果と分類**(`Result` で返す。ADR-0011):
  | 状況 | エラー | 分類 |
  |---|---|---|
  | 接続の失敗・接続のタイムアウト・切断・PUBACK のタイムアウト・Keep Alive の切れ・形式の誤り・Broker が混んでいる(0x88・0x89・0x97・0x9F) | `MqttUnavailable` | Retryable |
  | CONNACK の拒否(資格情報の誤り・許可がないなど) | `MqttConnectRejected` | NonRetryable |
  | PUBACK の拒否(ACL の 0x87 Not authorized・トピック名の誤りなど) | `MqttPublishRejected` | NonRetryable |
  | 送る前の検査(QoS・大きさ) | `MqttInvalidMessage` | NonRetryable |
- **再接続と送り直し**(`MqttPublisher`): 接続は必要になったときに張り、切れたら次の送信で張り直す。1 回の送信を shared/resilience の `Resilience` で包み、Retryable の失敗は kernel の RetryPolicy の待ち時間(Backoff + Jitter)の後に、再接続して送り直す(SDK に待ち時間の計算を作らない)。既定は試行 30 秒・初回を含めて 5 回・1 秒から倍々で上限 1 分。Circuit Breaker は使わない(デバイスは Broker のほかに送り先がなく、送れない間は呼び出し側が溜める。ADR-0039 §8)。
- **送り直しは新しい PUBLISH**(DUP のフラグを立てない): デバイスはセッションを引き継がない(clean start)ので、MQTT のセッションでの再送(同じパケット ID と DUP)の仕組みを持たない。PUBACK の前に切れて Broker に届いていた場合は重複するが、ペイロードの `boot`・`seq` が同じなので、iot-bridge が同じ ce_id にし、消費者が取り除く(ADR-0039 §5)。
- **トレース**: `MqttMessage.withTrace()` が `traceparent`(呼び出し元のものの子。なければ新しく始める。shared/resilience)と `correlationid`(kernel)をユーザープロパティに付ける(INTEGRATION_STANDARDS §2)。
- **資格情報**: パスワードは値を伏せる型(`MqttPassword`)で受け取り、`toString` に出さない。ハードコードしない(呼び出し側が環境変数などから渡す)。

### 3. ブリッジの側: HiveMQ MQTT Client
- iot-bridge(JVM)は HiveMQ MQTT Client で購読する。手動の ack で、Kafka の確認の後に PUBACK を返す(ADR-0039 §2・§5)。永続セッション(clean start = false、セッションの期限 24 時間。ADR-0039 §9)で購読する。
- 版は ⑤ で実装するときに最新の安定版を確かめて `libs.versions.toml` に固定する。Apache-2.0 のライブラリを本番のコードで使ってよいのは `services/iot-bridge/adapters` だけにする(⑤ で Konsist に足す)。
- 最小限の実装をブリッジに使わない理由: 購読・受け取った QoS 1 の手動の ack・永続セッションの再開と再送(同じパケット ID と DUP の扱い)が要り、プロトコルの実装の範囲が大きく広がる。JVM には保守されたライブラリがある。

### 4. 実際の Broker での確認
- 最小限の実装は、テスト用の Broker(ktor-network。jvm と native で動く)での振る舞いのテスト(`MqttPublisherSpec`)と、符号の固定のバイト列のテスト(`MqttCodecSpec`)に加えて、**実際の Mosquitto**(`images.env` の `MOSQUITTO_IMAGE`)に対する統合テスト(`MosquittoIT`。jvm)で確かめる。KMP のモジュールの jvm に `integrationTest` のコンパイルを足し、CI の integration の `platform` のグループで実行する。
- Mosquitto 2.1.2 で確かめた振る舞い:
  - ACL で書けないトピックへの QoS 1 の PUBLISH には、PUBACK の **0x87(Not authorized)**を返す(黙って捨てない)。
  - パスワードの誤りには、CONNACK の **0x87(Not authorized)**を返す(MQTT v5 の 0x86 Bad User Name or Password ではない)。どちらも NonRetryable。
  - MQTT v5 のユーザープロパティ(traceparent・correlationid)は、購読者にそのまま届く。

## Alternatives Considered
- **KMQTT を使い、足りないところを直して送る(フォーク)**: 受信の ack の順序と、送信の結果の返し方の両方を変える必要があり、作りの根本(ack を先に返す・`step()` を回す)に手が入る。保守が止まりかけている(2024-12 の後に版がない)ため、フォークを持ち続けることになる。不採用。
- **ブリッジもデバイスも同じ最小限の実装にする**: ブリッジに要る購読・手動の ack・永続セッションの再開は、実装の範囲が大きく、誤りやすい(重複や取りこぼしに直結する)。JVM は保守されたライブラリを使う。不採用。
- **デバイスの側も JVM で動かす(シミュレータを JVM にする)**: DoD が native のバイナリ(linuxX64・macosArm64)を求める。不採用。

## Consequences(トレードオフ)
- MQTT v5 の一部(約 600 行)を自分たちで持つ。仕様の外のふるまいの Broker に当たったときは、統合テストを足して直す。
- デバイスの側の送り直しは新しい PUBLISH なので、Broker に重複が届きうる(消費者の ce_id での冪等が前提。ADR-0039 §5)。
- シミュレータは TLS を持たない(ローカルは平文。ADR-0008)。TLS が要るときは #29 で足す。
- ブリッジは HiveMQ MQTT Client に依存する(Apache-2.0。⑤ で版を固定)。

## 改訂履歴
- 2026-10-24: P11 ③(#14)で作成した。
