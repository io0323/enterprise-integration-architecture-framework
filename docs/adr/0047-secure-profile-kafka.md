# ADR-0047: secure profile(Kafka の SASL_SSL と ACL)
- Status: Accepted
- Date: 2026-11-06
- Framework 参照章: 6, 12, 16
- 関連: ADR-0008(ローカルで縮退する範囲)、ADR-0016 §8(secure profile を P03 から外した)、ADR-0006(コマンド)、#26
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

## 改訂履歴
