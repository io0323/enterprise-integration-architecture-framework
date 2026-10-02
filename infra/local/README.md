# infra/local — ローカル基盤

EIAF の参照実装が使うミドルウェア一式を Docker Compose で起動する(ROADMAP P03)。
構成の決定は ADR-0016、S3 互換ストレージは ADR-0015、ミドルウェアの選定は ADR-0003 を参照。

## 前提

| 項目 | 要件 |
|---|---|
| Docker | Docker Engine 28.0.4 以上、Docker Compose v2.38.2 以上(確認した最も古い組み合わせ。CI の ubuntu-latest)。otel-collector と loki のヘルスチェックに image マウント(`volumes: [{type: image}]`)を使うため、これより古い版では healthy にならないことがある。ローカルでは Engine 29.8.0 / Compose v5.5.1 でも確認済み |
| メモリ | Docker に 8GB 以上を割り当てる。実測値は `docs/reports/p03-local-infrastructure.md`(core だけで約 1.8GB) |
| CPU | linux/amd64・linux/arm64 のどちらでも動く(全イメージがマルチアーキテクチャ) |
| ホストのツール | `make`、`bash`、`python3`、`curl`、`openssl`(開発用の証明書。OpenSSL 1.1.1 以上か LibreSSL 3 以上)、`ssh-keygen` / `sftp`(file・b2b の検査)、JDK 21(order profile。イメージの中身を Gradle で作る) |

## 起動と停止

```bash
make env                      # infra/local/.env と secrets/ を生成(初回。make up も自動で実行する)
make up                       # core を起動し、全コンテナが healthy になるまで待つ
make up PROFILE=cdc           # core + cdc(profile は常に core に積み上がる)
make schemas                  # 契約の Avro スキーマを Apicurio に登録する(make up の後。サービスは自動登録しない。ADR-0025 §2)
make up PROFILE="file chaos"  # 複数の profile を同時に起動する
make verify PROFILE=cdc       # healthy と各機能の疎通を検査する(PASS / FAIL を 1 行ずつ出力)
make up PROFILE=order         # core + order-service(先に installDist でイメージの中身を作り、migrate → serve の順に起動する)
make certs                    # 開発用の CA と mTLS の証明書を作る(make up も毎回確かめる。残りが 7 日を切ると作り直す)
make e2e                      # E2E のシナリオ(tests/e2e)。make up PROFILE=order で起動した基盤に対して実行する
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
| `order` | order-migrate(1 回だけ動いて終わる), order-service, seaweedfs, seaweedfs-init | API 連携のサンプル業務サービス(P05。ADR-0024)。SeaweedFS は監査のアンカーの保存先(ADR-0017 §5) |

Kafka の SSL / ACL を有効にする `secure` profile は未実装(Issue #26)。

## ポート一覧

ホスト側は **127.0.0.1 の 19000〜19999** だけを使う(ADR-0016 §6)。新しいポートは表にない番号を選び、この表に追記する。

| ホスト | コンテナ内のアドレス | サービス | profile | 用途 |
|---|---|---|---|---|
| 19080 | `apisix:9080` | APISIX | core | API Gateway(公開パス `/{domain}/v{n}/`。`/sales/v1/*` → order-service(order profile)。ヘルス `/_gateway/health`) |
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
| 19333 | `seaweedfs:8333` | SeaweedFS | file, b2b, order | S3 API(path-style: `http://localhost:19333/{bucket}/{key}`) |
| 19432 | `postgres:5432` | PostgreSQL | core | サービス別 DB |
| 19433 | `toxiproxy:19433` | PostgreSQL(Toxiproxy 経由) | chaos | 障害注入用 |
| 19443 | `order-service:8443` | order-service | order | API(`/v1/...`)。**mTLS だけ**(クライアント証明書は開発用 CA の署名で、SAN が `apisix`)。mTLS なしの接続を拒否することの検査と調査用。ヘルスチェック(8081。平文)は公開しない |
| 19474 | `toxiproxy:8474` | Toxiproxy | chaos | 管理 API(toxic の追加・削除) |
| 19883 | `mosquitto:1883` | Mosquitto | iot | MQTT(匿名接続は不可) |

## 認証情報

値はすべて `make env` がランダムに生成し、`infra/local/.env` と `infra/local/secrets/` に置く(どちらもコミットしない)。変数名は `env.example` を参照。

| 対象 | 利用者 | 値 |
|---|---|---|
| PostgreSQL(サービス別) | `order_service` / `inventory_service` / `payment_service` / `shipping_service` / `legacy_sim` / `batch_etl`(DB 名と同じ。DB の所有者で、マイグレーションに使う) | `.env` の `<SERVICE>_DB_PASSWORD`。他のサービスの DB には接続できない |
| PostgreSQL(サービス別のアプリ用) | `<DB 名>_app`(例 `order_service_app`)。表を所有せず、権限は各マイグレーションが付ける(監査記録は INSERT と SELECT だけ。ADR-0017) | `.env` の `<SERVICE>_APP_DB_PASSWORD`。**P04a ④ より前に作ったボリュームには無いので、`make clean` が必要** |
| PostgreSQL(CDC) | `debezium`(REPLICATION) | `DEBEZIUM_DB_PASSWORD` |
| Keycloak 管理 | `admin` | `KEYCLOAK_ADMIN_PASSWORD` |
| Keycloak client credentials | `eiaf-e2e`(スコープ `sales.order:read` / `sales.order:write`、`aud` = `order-api`) | `EIAF_E2E_CLIENT_SECRET` |
| Keycloak client credentials(2 つ目) | `eiaf-e2e-b`(`eiaf-e2e` と同じ設定。クライアントごとの Rate Limit・冪等の範囲の確認用。ADR-0023 §5) | `EIAF_E2E_B_CLIENT_SECRET`。**P05 ⑤c より前に作ったボリュームには無いので、`make clean` が必要** |
| Grafana | `admin` | `GRAFANA_ADMIN_PASSWORD` |
| MQTT | `MQTT_USERNAME` | `MQTT_PASSWORD` |
| S3(管理者) | `eiaf`(Admin)。バケットとポリシーの作成用 | `S3_ACCESS_KEY` / `S3_SECRET_KEY` |
| S3(監査のアンカーの書込み。サービスごと) | `eiaf-audit-{service}`(今は `eiaf-audit-order`)。バケット `eiaf-audit` の Read / List と、`anchors/{service}/` の下だけの Write。ほかのサービスのプレフィックス・バケットの管理操作・削除・Legal Hold はできない(ADR-0017 §7。Issue #43) | `ORDER_AUDIT_S3_ACCESS_KEY` / `ORDER_AUDIT_S3_SECRET_KEY`。以前の共有の `AUDIT_S3_*` は使われない(.env に残っていても害はない) |
| S3(監査の検査) | `eiaf-audit-verify`。バケット `eiaf-audit` の Read / List だけ。`make audit-verify` が使う | `AUDIT_VERIFY_S3_ACCESS_KEY` / `AUDIT_VERIFY_S3_SECRET_KEY` |
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
- **APISIX(ADR-0023)**: standalone(`apisix/apisix.yaml`)。`/sales/v1/*` を order-service の `/v1/*` に mTLS で送る(上流のタイムアウト 15 秒・再送なし)。
  - JWT はゲートウェイでも検証する(署名・exp・iss・aud・azp)。iss は `http://localhost:19180/realms/eiaf`、JWKS はコンテナから `http://keycloak:8080` で取る。
  - クライアント(azp)ごとの Rate Limit(60 件 / 60 秒。超えたら 429 + `Retry-After`)。外部の `traceparent` は捨て、ゲートウェイでトレースを始める。`X-Correlation-Id` がなければ付ける。
  - コンテナは鍵(`certs/apisix.key`)を読むため、ホストの利用者の uid とグループ 0 で動く。証明書は起動時に読み込むので、作り直したら `make up` で入れ替える(`docs/runbooks/dev-certificates.md`)。
- **Observability**: アプリは OTLP を `otel-collector` に送る。traces → Tempo、metrics → Prometheus、logs → Loki。Grafana のデータソースは provisioning 済み(trace ↔ log ↔ metric のリンクつき)。
  - アプリ(`platform/observability`。ADR-0018)の環境変数: `OTEL_SERVICE_NAME`、`OTEL_EXPORTER_OTLP_ENDPOINT`(OTLP/HTTP。ホストのアプリは `http://localhost:19318`、コンテナのアプリは `http://otel-collector:4318`。未設定なら OTLP に送らず標準出力だけ)、`OTEL_TRACES_SAMPLER_ARG`(起点のサンプリング率。既定 1.0)、`EIA_ENVIRONMENT`(既定 `local`)。
  - **ダッシュボード**: `grafana/provisioning/dashboards/eiaf/*.json` を provisioning で読み込む(フォルダ EIAF。画面では編集できないので、JSON を直してコミットする)。
    - `Order API — RED`(uid `eiaf-order-red`。http://localhost:19300/d/eiaf-order-red): Gateway(状態コード別の件数・ゲートウェイ自身の 401 / 429 / 502 / 504・処理時間)と order-service(ルート別の件数・エラーの割合と error.type 別・p50 / p95 / p99)。処理時間のパネルの赤い線はカタログの SLO(p99 500ms)。
    - 依存先の呼び出し: `eia.resilience.timeouts{kind=caller_deadline}`(呼び出し元の締め切りで打ち切った件数)。急増したらアラートの候補(しきい値は P14 で SLO と合わせて決める)。order-service は P05 では依存先を呼ばないので、P06・P07 までは空。
    - `make verify PROFILE=order` は、ダッシュボードが読み込まれていることと、全パネルの式が Prometheus でデータを返すことを確かめる(caller_deadline は合成の値 `service_name=eiaf-verify` で確かめる)。
  - 標準出力のログの形式は `EIA_LOG_FORMAT` で切り替える。既定は `json`(Loki と同じ項目)。手元で読むときは `EIA_LOG_FORMAT=console ./gradlew :services:order:app:run` のように `console` にする。
- **監査(ADR-0017)**: `seaweedfs-init`(file / b2b / order profile)が、`make up` のたびにバケット `eiaf-audit`(Object Lock)を作り、`seaweedfs/audit-bucket-policy.json` を設定し、完了のファイルを作って待機する(ヘルスチェックが完了を示すので、`make up` は初期化の完了まで待つ)。改竄の検査は `make audit-verify SERVICE=<name>`(終了コード 0 / 1 / 2。`docs/runbooks/audit-verify.md`)。
  - order-service は、1 分ごと(`ORDER_AUDIT_ANCHOR_INTERVAL`。アプリの既定は 1 時間)に、前回のアンカーからの差分を検証してアンカーを保存する(記録が増えていなければ保存しない)。成否はダッシュボード `Order API — RED` の「監査の記録」の行に出る。`make verify PROFILE=order` と `make e2e` は、保存を待ってから `make audit-verify SERVICE=order` が OK でアンカーがあることを確かめる(`scripts/audit-anchored.sh`)。
- **order-service(ADR-0024)**: `order-migrate` が所有者の資格情報でマイグレーションして終わり、`order-service` は完了を待ってから、アプリのロールの資格情報だけで起動する。API は mTLS だけで受ける。証明書は `infra/local/certs/`(.gitignore 済み。`make certs`)で、仕組みと期限切れのときの対処は `docs/runbooks/dev-certificates.md`。コンテナは開発用の鍵を読むため、ホストの利用者の uid で動く(root にはしない)。
- **Toxiproxy**: 起動時に `kafka-host`(19094)、`kafka-internal`(19095)、`postgres`(19433)の proxy を作る(`toxiproxy/toxiproxy.json`)。

## イメージの更新

版は `images.env` だけに書く(タグ + ダイジェスト。ADR-0016 §2)。

1. 最新の安定版のタグを確認する(プレリリース・RC は使わない)。
   - `JAVA_RUNTIME_IMAGE`(distroless)は版のタグがなく、タグ `nonroot` を上流が付け直す。今のダイジェストに更新し、`make up PROFILE=order && make verify PROFILE=order` で確かめる。
2. ダイジェストと対応アーキテクチャを確認する。`linux/amd64` と `linux/arm64` の両方が必要。

   ```bash
   docker buildx imagetools inspect apache/kafka:4.3.1   # Digest: と Platform: を確認する
   ```

3. `images.env` の行を `<repository>:<tag>@<digest>` に書き換える。
4. `make clean && make up PROFILE=<該当する profile> && make verify PROFILE=<同じ>` を実行し、PR にその結果を載せる。CI(`.github/workflows/infra.yml`)も全 profile で同じ検査を行う。

### Kafka Connect(Debezium)のイメージ

Kafka Connect は公開イメージを使わず、`images/kafka-connect/Dockerfile` で組み立てる(ADR-0016 §9)。Debezium の公式イメージは最新のパッチ版のタグが毎日付け直され、古いダイジェストが取得できなくなるため。

- ベースは `images.env` の `KAFKA_IMAGE`(`apache/kafka`)。`make up` が build 引数で渡す。
- 載せるもの: Debezium の Postgres コネクタと、Apicurio の Converter(どちらも Maven Central の成果物。版と SHA-256 を Dockerfile の `ARG` で固定)。
- 設定: `CONNECT_<NAME>` の環境変数が `connect-distributed.properties` の `<name>` になる(大文字 → 小文字、`_` → `.`)。`docker-compose.yml` の `kafka-connect` を参照。

版を上げる手順:

1. Maven Central で版を確認する(`io.debezium:debezium-connector-postgres`、`io.apicurio:apicurio-registry-distro-connect-converter`。Apicurio は `images.env` の Registry のサーバと同じ版)。
2. SHA-256 を、Maven Central の `.sha256` と、取得したファイルの値の両方で確かめる。

   ```bash
   url=https://repo1.maven.org/maven2/io/debezium/debezium-connector-postgres/3.6.3.Final/debezium-connector-postgres-3.6.3.Final-plugin.tar.gz
   curl -fsS "$url.sha256"; echo
   curl -fsSL "$url" | shasum -a 256
   ```

3. Dockerfile の `ARG`(版と SHA-256)を書き換える。
4. `make up PROFILE=cdc && make verify PROFILE=cdc` を実行し、PR にその結果を載せる(`make up` は `--build` で組み立て直す)。

## トラブルシューティング

| 症状 | 対処 |
|---|---|
| `make up` が `required variable ... is missing` で止まる | `make env` を実行する(新しいフェーズで変数が増えると、不足分だけ追記される) |
| tempo / loki が permission denied で起動しない | 以前の版(root で実行していた)で作ったボリュームが残っている。`make clean` でボリュームを削除してから `make up` |
| `postgres/init/` を変えたのに反映されない | 初期化スクリプトは初回だけ実行される。`make clean` でボリュームを削除してから `make up` |
| `make verify` が「アプリ用のロール ..._app がない」で失敗する | P04a ④ より前に作ったボリューム。`make clean` でボリュームを削除してから `make up` |
| Docker Desktop を再起動した後、otel-collector / loki が healthy にならない(`/probe/bin/wget: no such file or directory`) | 再起動でコンテナの image マウントが外れる。`make down && make up` でコンテナを作り直す(ボリュームは残る) |
| otel-collector / loki が healthy にならない | Docker が image マウントに対応していない。Docker Desktop / Engine を更新する |
| コンテナが再起動を繰り返す(OOM) | `make stats` で使用量を確認し、Docker に割り当てるメモリを増やすか、不要な profile を止める(`make down` して必要な profile だけ `make up`) |
| ポートが使用中で起動しない | 19000〜19999 を使う他のプロセスを止める(`lsof -iTCP:19092 -sTCP:LISTEN` など) |
| `make verify PROFILE=cdc` がコネクタの RUNNING で失敗する | `make logs SERVICE=kafka-connect` を確認する。`debezium` ユーザーは初期化スクリプトで作るため、古いボリュームなら `make clean` が必要 |
