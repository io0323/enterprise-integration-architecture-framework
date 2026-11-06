# ADR-0047: secure profile(Kafka の SASL_SSL と ACL・MQTT の TLS・管理 API の認証)
- Status: Accepted
- Date: 2026-11-06
- Framework 参照章: 6, 12, 16
- 関連: ADR-0008(ローカルで縮退する範囲)、ADR-0016 §8(secure profile を P03 から外した)、ADR-0006(コマンド)、ADR-0039 §3(MQTT の認証)、#26、#29
- 番号: P14 の計画で決めた番号(0044 障害注入 / 0045 SLO / 0046 イベントの再発行 / 0047 secure profile)

## Context
ADR-0008 は、Kafka の SSL / ACL を任意の `secure` profile で扱うとした。ADR-0016 §8 で P03 の範囲から外し、#26 に持ち越していた。これまでの Kafka はすべて PLAINTEXT で、Framework 12.3 のトピック単位の ACL を検証できていない。

P14 の計画では、`secure` を **任意の profile** にすると決めた。普段の起動は平文のまま変えず、`secure` は専用の CI のジョブで確かめる。#29(MQTT・管理 API など)も同じ profile に足す(P14 ⑨)。

## Decision
### 1. 重ねる compose のファイル
- `make up PROFILE=secure`(ほかの profile と並べてよい)のとき、`docker-compose.yml` に `docker-compose.secure.yml` を重ねる(Makefile の `COMPOSE`、`verify.sh`、`kafka-security.sh`)。
- profile だけでは、既にあるサービス(kafka)の設定を変えられない。そのため、ADR-0016 の積み上げ方式(profile ごとにサービスを足す)の例外として、ファイルを重ねる。
- `secure` を付けない起動は、今までと同じ平文のまま。

### 2. ホスト向けのリスナーを SASL_SSL にする
| 項目 | 値 |
|---|---|
| リスナー | `EXTERNAL`(`127.0.0.1:19092`、advertised `localhost:19092`)を `SASL_SSL` にする |
| TLS | 開発用の CA(`make certs`)の `kafka.crt`(SAN `kafka`・`localhost`)。鍵は PEM のまま読む |
| 認証 | SCRAM-SHA-512。資格情報はブローカーのメタデータ(KRaft)に置き、ブローカーの設定には書かない |
| 認可 | `StandardAuthorizer`。ACL のない操作は拒否する |

- 鍵はホストの利用者の 0600 のまま。1 回だけ動くコンテナ `kafka-secure-certs` が root で読み、ボリュームの中でブローカーの uid に渡す。ホストの鍵の権限は広げない。
- Toxiproxy 向けのリスナー(`TOXI_*`)は作らない。平文のまま super user になるため。**`secure` は `chaos`・`E2E=1` と同時に使えない**(`make up` が止める)。

### 3. ローカルでの縮退: コンテナ間のリスナーは今までどおり
- コンテナ間のリスナー(`PLAINTEXT` `kafka:9092`)と `CONTROLLER` は変えない。その principal(`User:ANONYMOUS`)を super user にする。
- そのため、サービス・Kafka Connect・exporter・ツールは設定を変えずに動く。**ローカルでは、サービス自身は ACL の制限を受けない。**
- ACL が効くのは、ホストから SASL_SSL でつなぐクライアントだけ。`make verify PROFILE=secure` は、カタログのユーザーとしてホストのリスナーにつなぎ、ACL の効き目を確かめる(§5)。
- **本番では、すべてのクライアントが自分の principal で認証する。** ローカルとの違い:
  - サービスは自分のユーザー(`SecretProvider` 経由の資格情報)で、SASL_SSL か mTLS でつなぐ。
  - Outbox の発行は Kafka Connect が行う。コネクタごとに `producer.override.*` で提供側のユーザーにする(`connector.client.config.override.policy`)。
  - カタログにない内部のトピック(生の CDC・Connect の `_connect.*`・トランザクションの ID・Consumer の内部)の ACL も要る。カタログから作る ACL(§4)は、連携の境界の分だけ。

### 4. ACL はカタログから作る(Framework 12.3)
`infra/local/scripts/kafka-acls.py` が `contracts/catalog/*.yaml` から作り、`kafka-security.sh`(`make up` が呼ぶ)がブローカーにそろえる。

| 連携 | 書く側(チャネルに Write) | 読む側(consumers) |
|---|---|---|
| event / cdc(pub-sub・cdc) | provider | チャネルに Read、group に Read、`{チャネル}.dlq` に Write |
| event(queue。コマンド。ADR-0006) | senders | 同上(受信サービスの 1 件だけ) |

- ユーザーはカタログの `system` の名前。パスワードは `infra/local/secrets/kafka/<ユーザー>`(なければ作る。`.gitignore` 済み)。
- そろえ方: 足りない ACL を足し、**カタログにない ACL とユーザーを消す**。カタログが ACL の唯一の出所になる。
- カタログから作らない形の ACL(PREFIXED・DENY・クラスタなど)があれば、黙って消さずに止める。
- `topics.conf` の機密区分(最後の列)の読み取りの制御は、この ACL で行う(読めるのはカタログの consumers だけ)。

### 5. 確かめること
- `make verify PROFILE=secure`(CI の infra の `verify (secure)` のジョブ):
  - TLS は CA とホスト名で検証できる。平文と誤ったパスワードは拒否される。
  - ACL がカタログとそろっている。
  - 書く側は自分のトピックに書け、他のトピックには書けない。読む側は購読するトピックと自分の Consumer Group だけを使える。
  - コンテナ間のリスナーは今までどおり使える。
- 書き込みの確認は、カタログに購読者のいない `iot.telemetry.reported.v1` に 1 件だけ書く(ほかの検査と分析に影響しない)。
- カタログから ACL を作る規則は、単体テスト(`kafka_acls_test.py`)を CI の `build` で毎回動かす。カタログの変更で作れなくなれば、そこで分かる。

### 6. MQTT のホスト向けのリスナーを TLS にする(#29)
- `iot` と並べたとき、ホストのポート 19883 を TLS のリスナー 8883 に向ける。証明書は開発用の CA の `mosquitto.crt`(SAN `mosquitto`・`localhost`)。
- 認証と ACL(デバイスごとの資格情報。ADR-0039 §3)は今までどおり。TLS の上で同じ資格情報を使う。
- コンテナ間のリスナー 1883(iot-bridge・監視の Telegraf・ヘルスチェック)は平文のまま。Kafka と同じ縮退(§3)。
- ホストのポートを入れ替えるので、ホストから平文でつなぐデバイスのシミュレータ(E2E)は `secure` では使えない。

### 7. Kafka Connect の REST を Basic 認証にする(#29)
- Connect の `BasicAuthSecurityRestExtension` を使う。ユーザーは `eiaf-admin` だけで、パスワードは `.env` の `CONNECT_REST_PASSWORD`。
- `connectors.sh`・`verify.sh`・E2E は、profile によらず資格情報を送る(認証のない Connect は無視する)。運用者の `curl` は `-u eiaf-admin:...` が要る(Runbook)。
- mTLS ではなく Basic 認証にしたのは、Connect の REST の利用者(スクリプトと運用者)が証明書を持たないため。TLS は付けず、127.0.0.1 だけへの公開で代替する(§8)。

### 8. 残りの転送路と受信口は、127.0.0.1 だけへの公開で代替する(#29)
次は `secure` でも平文・未認証のまま。どれも 127.0.0.1 にだけ公開する(ADR-0016 §6)。**本番ではすべての転送路を TLS にし、受信口に認証を付ける。**

| 対象 | ローカル | 本番 |
|---|---|---|
| S3(SeaweedFS)・Keycloak・APISIX | http | TLS(製品の機能か、前段の TLS の終端) |
| Prometheus(OTLP・remote write の受信)・Loki・Tempo・OTel Collector の OTLP の受信 | 未認証 | TLS と認証(mTLS かトークン)。ネットワークも分ける |
| Toxiproxy の API | 未認証 | 本番には置かない(障害の注入はローカルと CI だけ) |

TLS にしない理由: 対象が多く、それぞれの証明書・クライアントの設定(サービス・Grafana・Prometheus の scrape)が増える割に、ローカルで確かめられることが少ない。TLS の設定の仕方は、Kafka・MQTT・APISIX → サービスの mTLS(ADR-0024)・gRPC(ADR-0042)で示している。

### 9. Apicurio の REST を Keycloak の OIDC で守る(#29)
`secure` では、Apicurio の役割による認可を有効にする。役割は Keycloak のトークンの `realm_access.roles`。

| 操作 | 必要なもの |
|---|---|
| 読み取り(スキーマ・ルール・内容からの版の検索) | なし(匿名で読める) |
| 登録(版の追加・削除を含む) | `sr-developer`(Keycloak のクライアント `eiaf-schema-publisher`) |
| グローバルのルールの変更などの管理 | `sr-admin`(Keycloak のクライアント `eiaf-registry-admin`) |

- **読み取りを匿名で許す理由**: サービス・batch-etl・Connect の Converter は、起動時と読むときにスキーマを引く(内容からの検索は POST だが、読み取りとして扱われる)。全サービスにトークンの取得を足すと範囲が大きい。本番では、読み取りにも `sr-readonly` のトークンを求める。
- 書き込む側は client credentials のトークンを取る:
  - `schema-publish`(compose と `make schemas`): `SCHEMA_PUBLISH_TOKEN_URL`・`SCHEMA_PUBLISH_CLIENT_ID`・`SCHEMA_PUBLISH_CLIENT_SECRET`。3 つがそろわなければ止まる(黙って認証なしで送らない)。起動直後の Keycloak の遅い応答は再試行で待つ。
  - Kafka Connect の Converter(`legacy-juchu` の生の CDC のスキーマの自動登録): `connectors.sh` が `secure` のときだけ `apicurio.registry.auth.*` を足す。Client Secret は Connect の環境変数を `${env:...}` で参照する。
- Keycloak の realm に、役割(`sr-admin`・`sr-developer`・`sr-readonly`)と 2 つのクライアントを足した。realm は `secure` によらず同じ(役割とクライアントがあるだけで、普段の起動では使わない)。
- 管理の UI(ブラウザのログイン)は扱わない。REST だけを守る。

## Alternatives Considered
- **サービスも自分のユーザーで SASL_SSL につなぐ**: ACL の効き目をサービスの動作で確かめられる。全サービスの Kafka のクライアントと Connect・exporter・ツールの設定が変わり、範囲が大きい。今回は入れない(§3 の本番との違いとして残す)。
- **SASL/PLAIN**: 設定は簡単だが、パスワードそのものを送る。TLS の上でも、SCRAM より弱い。不採用。
- **mTLS のクライアント認証**: principal が証明書の名前になる。ユーザーごとに証明書が要り、開発用の CA の運用が増える。不採用。
- **ACL を手で書いたファイルにする**: カタログとずれる。カタログを唯一の出所にするため不採用。

## Consequences(トレードオフ)
- `secure` では、ホストから平文でつなぐツールは使えない(E2E、`make dlq-replay`)。`secure` は確かめるための profile で、普段は使わない。
- ローカルでは、サービス自身は ACL の制限を受けない(§3)。カタログの ACL が足りなくても、サービスの動作では気づかない。
- SCRAM のパスワードは、ユーザーを作るときにコンテナの中のコマンドの引数に一時的に出る(ホストのコマンドラインとログには出さない)。
- compose のファイルが 2 つになる。`secure` のときは、両方を読む compose のコマンドで操作する(`make` を使う)。
- MQTT のホストのポートは `!override` で入れ替える(Compose 2.24 以降の書き方)。
- Keycloak は realm を初回の起動でだけ読み込む。既にあるローカルの基盤で `secure` の Apicurio を使うには、`make clean` で作り直す(realm にクライアントが足りないと、トークンを取れない)。

## 改訂履歴
- 2026-11-06: MQTT の TLS(§6)、Kafka Connect の REST の Basic 認証(§7)、127.0.0.1 だけへの公開で代替する範囲(§8)を足した(P14 ⑨a。#29)。
- 2026-11-06: Apicurio の REST の OIDC(§9)を足した(P14 ⑨b。#29 を閉じる)。
