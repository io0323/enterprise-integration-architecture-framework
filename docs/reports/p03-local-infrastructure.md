# P03 Local Infrastructure 検証レポート

- 日付: 2026-09-26
- 対象: `infra/local/`(ADR-0015・ADR-0016)、Issue #5
- 環境: macOS(Apple Silicon)/ Docker Engine 29.8.0(linux/arm64)/ Compose v5.5.1 / Docker に割り当てたメモリ 7.75 GiB(`docker info` の MemTotal = 8,319,770,624 bytes)

## 1. DoD

| DoD | 結果 | 証跡 |
|---|---|---|
| profile ごとに `make up PROFILE=<name>` で全コンテナが healthy になる | **達成**(core / cdc / iot / file / b2b / chaos) | §2。`make up` は `docker compose up --wait` で全コンテナが healthy になるまで待ち、失敗すると終了コードが 0 以外になる。`make verify` の `[health]` で全サービスが `running/healthy` |
| README に起動手順とポート一覧がある | **達成** | `infra/local/README.md`(起動と停止、profile、ポート一覧、認証情報、イメージの更新、トラブルシューティング) |
| Apicurio の互換性ルールを FULL_TRANSITIVE に設定する(ADR-0014) | **達成** | `make verify` の `[core] Apicurio のグローバル COMPATIBILITY ルールが FULL_TRANSITIVE`。加えて、FULL 非互換の版(default のない項目の追加)の登録が HTTP 400 で拒否され、FULL 互換の版(default 付きの項目の追加)は受理されることを検査している |

`secure` profile(Kafka の SASL_SSL / ACL)は P03 の範囲から外した(ADR-0016 §8、Issue #26)。

## 2. profile ごとの検証結果

各コミットの時点で、そのコミットで追加した profile を空の状態(`make clean`)から起動して検証した。最後に HEAD で全 profile を順に起動し直した(ボリュームを残したまま `make down` → `make up`。2 回目以降の起動も確かめている)。

| profile | コミット時点 | HEAD | 主な検査(`make verify`) |
|---|---|---|---|
| core | PASS 33 / FAIL 0 | PASS 33 / FAIL 0 | Apicurio のルールと拒否・受理、Keycloak のトークン(`iss` / `aud=order-api` / `sales.order:*`)、APISIX のルート、PostgreSQL のサービス別 DB と他 DB への接続拒否・`wal_level=logical`、Kafka の produce / consume と自動作成の無効、OTLP の trace / log / metric が Tempo / Loki / Prometheus で検索できる、Prometheus の scrape 先がすべて up、Grafana の 3 データソースの health |
| cdc | PASS 41 / FAIL 0 | PASS 41 / FAIL 0 | PostgresConnector と Apicurio の AvroConverter が使える、`debezium` の REPLICATION、一時テーブルに Debezium のコネクタを登録し、スナップショット(op=r)と INSERT(op=c)の 2 件が Kafka に届く。コネクタ・オフセット・スロット・トピックを削除する |
| iot | PASS 38 / FAIL 0 | PASS 38 / FAIL 0 | 認証つきで QoS 1 の publish / subscribe、匿名接続と誤ったパスワードの拒否、ホストのポート |
| file | PASS 55 / FAIL 0 | PASS 55 / FAIL 0 | S3 の互換性 18 項目(§4)、SFTP の公開鍵認証での put(一時名)→ rename → get → rm、パスワード認証の拒否 |
| b2b | PASS 56 / FAIL 0 | PASS 56 / FAIL 0 | S3 の互換性、取引先向け SFTP の同じ操作、パスワード認証と社内向けの鍵の拒否 |
| chaos | PASS 43 / FAIL 0 | PASS 43 / FAIL 0 | 3 つの proxy、TOXI_HOST(advertised `localhost:19094`)・TOXI_INTERNAL(`toxiproxy:19095`)、Toxiproxy 経由の produce / consume、latency の toxic で応答が遅くなる(1183ms → 8799ms)、proxy を無効にすると PostgreSQL に接続できず、有効に戻すと接続できる |

件数には、core の検査(33 件)とその profile のサービスの healthy の確認を含む。

## 3. メモリ使用量(docker stats)

HEAD で profile ごとに起動し、`make verify` の後 60 秒おいてから `make stats` で測った(単位 MiB)。

| コンテナ | 上限 (MiB) | core | cdc | iot | file | b2b | chaos |
|---|---:|---:|---:|---:|---:|---:|---:|
| apicurio | 640 | 235 | 234 | 231 | 227 | 226 | 243 |
| apisix | 256 | 49 | 50 | 61 | 49 | 50 | 57 |
| grafana | 512 | 271 | 272 | 264 | 249 | 262 | 265 |
| kafka | 768 | 376 | 394 | 398 | 401 | 363 | 407 |
| keycloak | 896 | 480 | 474 | 482 | 484 | 469 | 484 |
| loki | 384 | 70 | 71 | 72 | 73 | 80 | 75 |
| otel-collector | 256 | 96 | 52 | 61 | 50 | 48 | 94 |
| postgres | 384 | 76 | 79 | 81 | 74 | 76 | 75 |
| prometheus | 384 | 63 | 68 | 79 | 71 | 69 | 75 |
| tempo | 384 | 56 | 75 | 52 | 84 | 78 | 77 |
| kafka-connect | 1024 | — | 583 | — | — | — | — |
| mosquitto | 64 | — | — | 16 | — | — | — |
| seaweedfs | 512 | — | — | — | 143 | 135 | — |
| sftp | 64 | — | — | — | 9 | — | — |
| sftp-b2b | 64 | — | — | — | — | 4 | — |
| toxiproxy | 64 | — | — | — | — | — | 17 |
| **合計** | | **1772** / 4864 | **2352** / 5888 | **1796** / 4928 | **1914** / 5440 | **1861** / 5440 | **1870** / 4928 |

- core だけで約 1.8 GB、最も重い cdc でも約 2.4 GB。Docker に 7.75 GiB を割り当てた環境で、どの profile も余裕がある。上限の合計(4.8〜5.9 GB)も割り当ての範囲に収まる。
- 計測の途中で見つけて直したこと:
  - kafka-connect: ヒープ 512 MiB・上限 896 MiB では、使用量が上限の 99.8%(894 MiB。うち page cache 約 225 MiB)に達した。ヒープを 384 MiB、上限を 1 GiB にして 57% になった。
  - grafana: 上限 256 MiB で 96.6%、384 MiB で 91% と、上限まで使い切る傾向があった。上限を 512 MiB にし、`GOMEMLIMIT=384MiB` で Go のランタイムに GC させて 50% 前後になった。
- 16GB の開発マシンで Docker に 8GB を割り当てれば、core に加えて複数の profile を同時に起動できる(例: core + cdc + chaos で約 2.5 GB)。

## 4. S3 互換ストレージ(SeaweedFS 4.47)の互換性

`make verify PROFILE=file` の S3 の項目(`scripts/s3-compat.sh`、AWS CLI 2.37.4、path-style)。すべて成功した。

- バケットを作成できる / put-object できる / get-object で同じ内容を取得できる / list-objects-v2(prefix 指定)/ copy-object
- マルチパートアップロード(2 パート・6 MiB)で完全な内容を取得できる / abort できる
- 署名付き URL(GET)で認証情報なしに取得できる / 改ざんした署名付き URL を拒否する(HTTP 403)/ ホスト向けの署名付き URL が path-style になる
- バージョニングを有効にでき、古い版を version-id で取得できる
- Object Lock 付きのバケットを作成でき、Enabled かつバージョニングが有効 / COMPLIANCE の保持期限つきで put でき、期限内の版は削除できない / 期限の経過後に検査用のバケットを削除できる
- ホストのポート 19333 で応答する

## 5. イメージ

すべて `infra/local/images.env` にタグ + ダイジェストで固定した。amd64 / arm64 は `docker buildx imagetools inspect` のマニフェストで確認した。

| 変数 | イメージ | タグ | ダイジェスト | amd64 | arm64 |
|---|---|---|---|---|---|
| `KAFKA_IMAGE` | `apache/kafka` | `4.3.1` | `sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837` | ✅ | ✅ |
| `DEBEZIUM_CONNECT_IMAGE` | `quay.io/debezium/connect` | `3.6.3.Final` | `sha256:c51aab05999cfbe2ad131ff89094c7b98610df90e2403f777cc6cc2878e5a22e` | ✅ | ✅ |
| `APICURIO_REGISTRY_IMAGE` | `apicurio/apicurio-registry` | `3.3.3` | `sha256:c9cae4c90ce46538abf673c68eb591345f23bcc6c2aa6bfd47189adcf609d8f1` | ✅ | ✅ |
| `MOSQUITTO_IMAGE` | `eclipse-mosquitto` | `2.1.2-alpine` | `sha256:38c0da4f2ef84284d47b3b3eeea1cb3bdeabe81ee10caf0cd5c5ff61ee3ea408` | ✅ | ✅ |
| `POSTGRES_IMAGE` | `postgres` | `18.6-alpine` | `sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873` | ✅ | ✅ |
| `KEYCLOAK_IMAGE` | `quay.io/keycloak/keycloak` | `26.7.4` | `sha256:82a77884f3af238beab1e7afd63b5f530e1b5c0590bd7aa60b40a40463e29b2c` | ✅ | ✅ |
| `APISIX_IMAGE` | `apache/apisix` | `3.18.0-debian` | `sha256:84e6b5e787e9f889ebff88161cb9a16599bafcffa236c6b54c7f779a0655940d` | ✅ | ✅ |
| `SEAWEEDFS_IMAGE` | `chrislusf/seaweedfs` | `4.47` | `sha256:ce9e796f1fe6f06968f4c04bdaf8f678dad9c8acdfef3d244133d71bfa6bf882` | ✅ | ✅ |
| `SFTP_IMAGE` | `linuxserver/openssh-server` | `10.3_p1-r1-ls237` | `sha256:946fa26105e0ec212fdf821b9ddc59aab65f2c2d07c02b25ff0f5001fc332ff0` | ✅ | ✅ |
| `OTEL_COLLECTOR_IMAGE` | `otel/opentelemetry-collector-contrib` | `0.161.0` | `sha256:fd328de2552466ad78385e1b1289c3f2402b1c45f265b252aab1955b42845ac1` | ✅ | ✅ |
| `PROMETHEUS_IMAGE` | `prom/prometheus` | `v3.15.0` | `sha256:efd719c99d83b060d9daefdcf00360461adf279f45ef5391f8d111892118753e` | ✅ | ✅ |
| `GRAFANA_IMAGE` | `grafana/grafana` | `13.2.2` | `sha256:ac461fb352abc50da10a51c7d02462e9c05488f11f53f14b3ad79a8145f638a0` | ✅ | ✅ |
| `TEMPO_IMAGE` | `grafana/tempo` | `3.0.3` | `sha256:0296560ac66f8a3600d7fb3014a52c189d4d9c3549ad6ff441bf2409855d68d5` | ✅ | ✅ |
| `LOKI_IMAGE` | `grafana/loki` | `3.7.8` | `sha256:1107dd5274e0ada47e42472b7a7e71f3b2a2fe878878108f3e2f9e51528f0193` | ✅ | ✅ |
| `TOXIPROXY_IMAGE` | `ghcr.io/shopify/toxiproxy` | `2.12.0` | `sha256:9378ed52a28bc50edc1350f936f518f31fa95f0d15917d6eb40b8e376d1a214e` | ✅ | ✅ |
| `PROBE_IMAGE` | `busybox` | `1.38.0-musl` | `sha256:ea2b9914a16a4ac1981994af97b318f7c7d4db76b580c56177f08bf76f4a0be8` | ✅ | ✅ |
| `AWS_CLI_IMAGE` | `amazon/aws-cli` | `2.37.4` | `sha256:fdd8d1fcbea9c371678dee5a40df8b178c7a781b4586605756ee28114c97ead6` | ✅ | ✅ |

- 採らなかったイメージ: `minio/minio`(上流がアーカイブ済みで、公開イメージも取得できない。ADR-0015)、`atmoz/sftp`(amd64 のみ)、`emberstack/sftp`(2024 年以降更新なし)。
- `PROBE_IMAGE` はシェルのない otel-collector と loki のヘルスチェック用(image マウント)、`AWS_CLI_IMAGE` は `make verify` の S3 の検査用(ADR-0016 §4)。

## 6. 見つけて直した問題

| 問題 | 原因 | 対処 |
|---|---|---|
| APISIX が healthy にならない | `CMD-SHELL` は `/bin/sh`(dash)で実行され、`/dev/tcp` が使えない | `bash -c` で実行する |
| Apicurio が healthy にならない | 3.x のヘルスチェックとメトリクスは管理ポート 9000(`/health/ready`、`/metrics`) | ポートとパスを直した |
| 2 回目以降の起動で mosquitto が終了する | `mosquitto_passwd -c` は既存のファイルに書けない。パスワードファイルをボリュームに置いていた | 起動のたびにコンテナ内(`/mosquitto/passwd`)に作り直す |
| 変数を追加した後の `make clean` が失敗する | `.env` に新しい変数がないと compose の変数展開が失敗する | `down` / `clean` / `logs` / `ps` も先に `make env` を実行する |
| cdc の検証の 2 回目が失敗する | 同じ名前のコネクタが、Connect に残ったオフセットから再開してスナップショットをしない | 実行ごとに名前を変え、後片付けでオフセットも削除する |
| Grafana のデータソースの health が起動直後に失敗することがある | Tempo の準備が整う前に検査していた | ほかの検査と同じく再試行する |

## 7. 残っていること

- CI(`.github/workflows/infra.yml`、ubuntu-latest・amd64・Docker Engine 28.0.4 / Compose v2.38.2): image マウントのヘルスチェックを含めて core / iot / file / b2b / chaos は初回から成功した。cdc は、イメージの取得でランナーのディスク使用率が 90% を超えて Loki が WAL の書き込みを止め(`disk usage exceeded threshold`)、Loki の検査だけが失敗した。ワークフローで使わない SDK を削除して空きを作るようにした。
- secure profile は Issue #26。
- Object Lock の Compliance モードの厳密さ(管理者の権限での削除・保持期限の短縮)は P04a の Audit の統合テストで確かめる(ADR-0015)。
