# infra/local — ローカル基盤

EIAF の参照実装が使うミドルウェア一式を Docker Compose で起動する(ROADMAP P03)。
構成の決定は ADR-0016、S3 互換ストレージは ADR-0015、ミドルウェアの選定は ADR-0003 を参照。

## 前提

| 項目 | 要件 |
|---|---|
| Docker | Docker Engine 28.0.4 以上、Docker Compose v2.38.2 以上(確認した最も古い組み合わせ。CI の ubuntu-latest)。otel-collector と loki のヘルスチェックに image マウント(`volumes: [{type: image}]`)を使うため、これより古い版では healthy にならないことがある。ローカルでは Engine 29.8.0 / Compose v5.5.1 でも確認済み |
| メモリ | Docker に 8GB 以上を割り当てる。実測値は `docs/reports/p03-local-infrastructure.md`(core だけで約 1.8GB) |
| CPU | linux/amd64・linux/arm64 のどちらでも動く(全イメージがマルチアーキテクチャ) |
| ホストのツール | `make`、`bash`、`python3`、`curl`、`ssh-keygen` / `sftp`(file・b2b の検査) |

## 起動と停止

```bash
make env                      # infra/local/.env と secrets/ を生成(初回。make up も自動で実行する)
make up                       # core を起動し、全コンテナが healthy になるまで待つ
make up PROFILE=cdc           # core + cdc(profile は常に core に積み上がる)
make up PROFILE="file chaos"  # 複数の profile を同時に起動する
make verify PROFILE=cdc       # healthy と各機能の疎通を検査する(PASS / FAIL を 1 行ずつ出力)
make ps                       # 状態
make logs SERVICE=kafka       # ログ
make stats                    # メモリ使用量(docker stats)
make down                     # 停止(データは残る)
make clean                    # 停止してボリュームも削除する
```

`docker compose` を直接使う場合は、イメージと秘密情報のファイルを両方指定する。

```bash
docker compose -f infra/local/docker-compose.yml --env-file infra/local/images.env --env-file infra/local/.env --profile core ps
```

## profile

| profile | サービス | 用途 |
|---|---|---|
| `core` | kafka, apicurio, postgres, keycloak, apisix, otel-collector, prometheus, tempo, loki, grafana | 常に起動する |
| `cdc` | kafka-connect(Debezium) | Outbox + CDC(P06) |
| `iot` | mosquitto | MQTT(P11) |
| `file` | seaweedfs(S3), sftp | ファイル連携(P09)、Audit のアンカー(P04a) |
| `b2b` | seaweedfs(S3), sftp-b2b | B2B / EDI(P12) |
| `chaos` | toxiproxy | 障害注入(P04b, P14) |

Kafka の SSL / ACL を有効にする `secure` profile は未実装(Issue #26)。

## ポート一覧

ホスト側は **127.0.0.1 の 19000〜19999** だけを使う(ADR-0016 §6)。新しいポートは表にない番号を選び、この表に追記する。

| ホスト | コンテナ内のアドレス | サービス | profile | 用途 |
|---|---|---|---|---|
| 19080 | `apisix:9080` | APISIX | core | API Gateway(公開パス `/{domain}/v{n}/`。ヘルス `/_gateway/health`) |
| 19081 | `apicurio:8080` | Apicurio Registry | core | REST API(`/apis/registry/v3`、Confluent 互換 `/apis/ccompat/v7`) |
| 19083 | `kafka-connect:8083` | Kafka Connect(Debezium) | cdc | Connect REST API |
| 19090 | `prometheus:9090` | Prometheus | core | UI / API(OTLP 受信 `/api/v1/otlp`) |
| 19092 | `kafka:19092` | Kafka | core | ホストのクライアント用の `EXTERNAL` リスナー(advertised `localhost:19092`)。コンテナからは `kafka:9092`(`PLAINTEXT`) |
| 19094 | `toxiproxy:19094` → `kafka:9094` | Kafka(Toxiproxy 経由) | chaos | proxy `kafka-host`。ホスト用の `TOXI_HOST` リスナー(advertised `localhost:19094`)。コンテナからは proxy `kafka-internal` の `toxiproxy:19095` → `kafka:9095`(`TOXI_INTERNAL`) |
| 19180 | `keycloak:8080` | Keycloak | core | OIDC(realm `eiaf`)。管理コンソール `/admin` |
| 19222 | `sftp:2222` | SFTP(社内向け) | file | 利用者 `eiaf-file`、公開鍵認証のみ |
| 19223 | `sftp-b2b:2222` | SFTP(取引先向け) | b2b | 利用者 `partner01`、公開鍵認証のみ |
| 19300 | `grafana:3000` | Grafana | core | UI(利用者 `admin`) |
| 19310 | `loki:3100` | Loki | core | API |
| 19317 | `otel-collector:4317` | OTel Collector | core | OTLP gRPC |
| 19318 | `otel-collector:4318` | OTel Collector | core | OTLP HTTP |
| 19320 | `tempo:3200` | Tempo | core | API |
| 19333 | `seaweedfs:8333` | SeaweedFS | file, b2b | S3 API(path-style: `http://localhost:19333/{bucket}/{key}`) |
| 19432 | `postgres:5432` | PostgreSQL | core | サービス別 DB |
| 19433 | `toxiproxy:19433` | PostgreSQL(Toxiproxy 経由) | chaos | 障害注入用 |
| 19474 | `toxiproxy:8474` | Toxiproxy | chaos | 管理 API(toxic の追加・削除) |
| 19883 | `mosquitto:1883` | Mosquitto | iot | MQTT(匿名接続は不可) |

## 認証情報

値はすべて `make env` がランダムに生成し、`infra/local/.env` と `infra/local/secrets/` に置く(どちらもコミットしない)。変数名は `env.example` を参照。

| 対象 | 利用者 | 値 |
|---|---|---|
| PostgreSQL(サービス別) | `order_service` / `inventory_service` / `payment_service` / `shipping_service` / `legacy_sim` / `batch_etl`(DB 名と同じ) | `.env` の `<SERVICE>_DB_PASSWORD`。他のサービスの DB には接続できない |
| PostgreSQL(CDC) | `debezium`(REPLICATION) | `DEBEZIUM_DB_PASSWORD` |
| Keycloak 管理 | `admin` | `KEYCLOAK_ADMIN_PASSWORD` |
| Keycloak client credentials | `eiaf-e2e`(スコープ `sales.order:read` / `sales.order:write`、`aud` = `order-api`) | `EIAF_E2E_CLIENT_SECRET` |
| Grafana | `admin` | `GRAFANA_ADMIN_PASSWORD` |
| MQTT | `MQTT_USERNAME` | `MQTT_PASSWORD` |
| S3 | — | `S3_ACCESS_KEY` / `S3_SECRET_KEY` |
| SFTP | `eiaf-file` / `partner01` | 秘密鍵 `secrets/sftp-file` / `secrets/sftp-b2b` |

トークンの取得例:

```bash
source infra/local/.env
curl -s -X POST http://localhost:19180/realms/eiaf/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=eiaf-e2e --data-urlencode "client_secret=$EIAF_E2E_CLIENT_SECRET" \
  --data-urlencode 'scope=sales.order:read sales.order:write'
```

## 主な設定

- **Kafka**: KRaft の単一ブローカー。トピックの自動作成は無効(`auto.create.topics.enable=false`)。トピックは各フェーズの初期化で作る。
- **Apicurio**: ストレージは PostgreSQL。既定のグローバルルールは `COMPATIBILITY=FULL_TRANSITIVE`、`VALIDITY=FULL`(ADR-0014)。
- **Keycloak**: `iss` は常に `http://localhost:19180/realms/eiaf`。コンテナ内のサービスも JWKS は `http://keycloak:8080` から取れる。
- **PostgreSQL**: `wal_level=logical`(CDC)。初期化スクリプト(`postgres/init/`)はボリュームが空のときだけ実行される。
- **APISIX**: standalone(`apisix/apisix.yaml`)。業務ルートは P05 以降で追加する。
- **Observability**: アプリは OTLP を `otel-collector` に送る。traces → Tempo、metrics → Prometheus、logs → Loki。Grafana のデータソースは provisioning 済み(trace ↔ log ↔ metric のリンクつき)。
- **Toxiproxy**: 起動時に `kafka-host`(19094)、`kafka-internal`(19095)、`postgres`(19433)の proxy を作る(`toxiproxy/toxiproxy.json`)。

## イメージの更新

版は `images.env` だけに書く(タグ + ダイジェスト。ADR-0016 §2)。

1. 最新の安定版のタグを確認する(プレリリース・RC は使わない)。
2. ダイジェストと対応アーキテクチャを確認する。`linux/amd64` と `linux/arm64` の両方が必要。

   ```bash
   docker buildx imagetools inspect apache/kafka:4.3.1   # Digest: と Platform: を確認する
   ```

3. `images.env` の行を `<repository>:<tag>@<digest>` に書き換える。
4. `make clean && make up PROFILE=<該当する profile> && make verify PROFILE=<同じ>` を実行し、PR にその結果を載せる。CI(`.github/workflows/infra.yml`)も全 profile で同じ検査を行う。

## トラブルシューティング

| 症状 | 対処 |
|---|---|
| `make up` が `required variable ... is missing` で止まる | `make env` を実行する(新しいフェーズで変数が増えると、不足分だけ追記される) |
| tempo / loki が permission denied で起動しない | 以前の版(root で実行していた)で作ったボリュームが残っている。`make clean` でボリュームを削除してから `make up` |
| `postgres/init/` を変えたのに反映されない | 初期化スクリプトは初回だけ実行される。`make clean` でボリュームを削除してから `make up` |
| otel-collector / loki が healthy にならない | Docker が image マウントに対応していない。Docker Desktop / Engine を更新する |
| コンテナが再起動を繰り返す(OOM) | `make stats` で使用量を確認し、Docker に割り当てるメモリを増やすか、不要な profile を止める(`make down` して必要な profile だけ `make up`) |
| ポートが使用中で起動しない | 19000〜19999 を使う他のプロセスを止める(`lsof -iTCP:19092 -sTCP:LISTEN` など) |
| `make verify PROFILE=cdc` がコネクタの RUNNING で失敗する | `make logs SERVICE=kafka-connect` を確認する。`debezium` ユーザーは初期化スクリプトで作るため、古いボリュームなら `make clean` が必要 |
