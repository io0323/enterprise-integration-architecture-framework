# ADR-0016: ローカル基盤の Docker Compose 構成
- Status: Accepted
- Date: 2026-09-26
- Framework 参照章: 2, 12, 13, 14, 17
- 関連: ADR-0003(ミドルウェア選定)、ADR-0008(ローカルで縮退する範囲)、ADR-0014(互換性モード)、ADR-0015(S3 互換ストレージ)

## Context
P03 で `infra/local/` に ADR-0003 のミドルウェア一式を Docker Compose で構築する。決める必要があるのは次の点。
- メモリ 16GB の開発マシン(Docker に割り当てるのは 8〜10GB)で動くよう、どう分割して起動するか
- イメージの版の固定方法と、P04a 以降の Testcontainers との共有
- シェルや HTTP クライアントを持たないイメージのヘルスチェック(`make up` は全コンテナが healthy になるまで待つ)
- ホスト側のポート、秘密情報の置き場所
- Kafka・Apicurio・Keycloak の、後続フェーズの前提になる設定

## Decision
### 1. profile は core を土台に積み上げる
| profile | サービス | 用途(フェーズ) |
|---|---|---|
| `core` | kafka, apicurio, postgres, keycloak, apisix, otel-collector, prometheus, tempo, loki, grafana | すべて(P04a〜) |
| `cdc` | kafka-connect(Debezium) | Outbox + CDC(P06)、レガシー CDC |
| `iot` | mosquitto | IoT(P11) |
| `file` | seaweedfs, sftp | File(P09)、Audit のアンカー(P04a) |
| `b2b` | seaweedfs, sftp-b2b | B2B / EDI(P12) |
| `chaos` | toxiproxy | 障害注入(P04b, P14) |

- `make up PROFILE=<name>` は常に `--profile core --profile <name>` で起動する(空白区切りで複数指定できる)。core 以外の profile のサービスは core のサービスに `depends_on` するため、core を外して起動する使い方は想定しない。
- 各コンテナに `mem_limit` を設定し、JVM はヒープの上限を明示する。実測値は `docs/reports/p03-local-infrastructure.md` に記録する。
- `make up` は `docker compose up --wait` で全コンテナが healthy になるまで待ち、`make verify` は healthy と各 profile の機能の疎通(`infra/local/scripts/verify.sh`)を検査する。CI(`.github/workflows/infra.yml`)も profile ごとに同じ検査を実行する。

### 2. イメージはタグとダイジェストで固定し、1 つのファイルにまとめる
- すべてのイメージを `infra/local/images.env` に `<NAME>_IMAGE=<repository>:<tag>@sha256:<digest>` の形で書く。`docker-compose.yml` はこの変数だけを参照する(`image: ${KAFKA_IMAGE:?}`)。どちらも CI(`compose-config` ジョブ)で検査する。
- ダイジェストはマルチアーキテクチャのインデックスのダイジェストとし、**linux/amd64 と linux/arm64 の両方を含む**ものだけを使う(Apple Silicon の開発機と CI の amd64 ランナーで同じ版を使うため)。
- 版は採用時点の最新の安定版とし、`docker buildx imagetools inspect <image>:<tag>` でダイジェストと対応アーキテクチャを確認して更新する(手順は `infra/local/README.md`)。

### 3. SFTP は linuxserver/openssh-server を使う
- 当初の候補の `atmoz/sftp` は、2026-09 時点でも amd64 だけのイメージしか公開していない。`emberstack/sftp` は 2024 年以降更新がない。
- マルチアーキテクチャで更新が続いている `linuxserver/openssh-server` を使い、公開鍵認証だけを許可する。社内向け(`sftp`、file profile)と取引先向け(`sftp-b2b`、b2b profile)で、利用者・鍵・保存領域を分ける。

### 4. ヘルスチェックは既存の機能だけで行い、独自のイメージは作らない
Docker のヘルスチェックはコンテナの中で実行されるため、イメージ内で実行できる手段が要る。次の順で選び、**独自のイメージは作らない**。

| 手段 | 対象 |
|---|---|
| イメージに含まれるツール(`curl` / `wget` / `pg_isready` / `mosquitto_sub` / `nc` / Kafka の CLI) | kafka, kafka-connect, apicurio, postgres, prometheus, grafana, mosquitto, seaweedfs, sftp |
| イメージに含まれる本体の機能 | tempo(`/tempo -health`: `/ready` を確認して終了する)、toxiproxy(`/toxiproxy-cli list`: API に問い合わせる) |
| bash の `/dev/tcp` で HTTP を送る | keycloak(管理ポートの `/health/ready`)、apisix(`/status/ready`)。どちらも bash はあるが HTTP クライアントがない |
| 既存の公開イメージ `busybox`(静的リンク)を読み取り専用で `/probe` にマウントし、その `wget` を使う | otel-collector(`health_check` 拡張の `:13133`)、loki(`/ready`)。どちらもシェルも HTTP クライアントもない |

- busybox は `images.env` の `PROBE_IMAGE` に、ほかのイメージと同じくタグとダイジェストで固定する。マウントは Docker の image マウント(`volumes: [{type: image, source: ..., target: /probe}]`)で行う。動作は Docker Engine 29.8.0 / Compose v5.5.1 で確認した。古い Docker Engine は image マウントに対応していない。
- 外からの確認(`verify.sh` から HTTP で叩く)だけにする案は、`up --wait` と `depends_on: condition: service_healthy` が使えなくなるため採らない。外からの確認は、ヘルスチェックに加えて `verify.sh` で行う。

### 5. Testcontainers も images.env を読む(実装は P04a)
- P04a 以降の統合テスト(Testcontainers)は、compose と同じ `infra/local/images.env` のイメージを使う。版がずれると、ローカル基盤で確かめたことと統合テストの結果が食い違うため。
- 方針:
  - `build-logic` の JVM の convention(`eia.jvm-library` / `eia.jvm-service`)で、`Test` タスク(`integrationTest` を含む)に `infra/local/images.env` を入力ファイルとして宣言し(変更時にテストを再実行させる)、パスをシステムプロパティ `eia.images.file` で渡す。
  - テスト用の小さなヘルパー(例 `InfraImages.get("KAFKA_IMAGE"): DockerImageName`)が `KEY=VALUE` の行を読み、コメントと空行を無視して `DockerImageName.parse` する。見つからないキーは例外にする(黙って `latest` にしない)。
  - Testcontainers のモジュールが特定のイメージ名を要求する場合(例 `KafkaContainer`)は `asCompatibleSubstituteFor` で互換を宣言する。
- ヘルパーを置くモジュール(テスト専用の `platform` のモジュールか `build-logic` か)は P04a で決め、この ADR に追記する。

### 6. ホスト側のポートと秘密情報
- ホスト側のポートは **127.0.0.1 の 19000〜19999** に割り当てる(他の開発ツールの既定ポートとの衝突を避け、LAN には公開しない)。一覧は `infra/local/README.md`。
- 秘密情報(DB・Keycloak・Grafana・MQTT・S3 の資格情報、SFTP の鍵)は `make env`(`scripts/init-env.sh`)がランダムに生成し、`infra/local/.env` と `infra/local/secrets/` に置く(どちらも .gitignore 済み)。`.env.example` には変数名だけを書く。compose は `${VAR:?}` で必須にし、未生成なら起動しない。
- PostgreSQL はサービスごとに DB とユーザーを分け(Shared Database 禁止)、他のサービスの DB には接続できない。

### 7. 後続フェーズの前提になる設定
| 対象 | 設定 | 理由 |
|---|---|---|
| Kafka | `auto.create.topics.enable=false` | 命名規約(Framework 6.2)外のトピックが暗黙に作られるのを防ぐ。トピックは各フェーズの初期化で明示的に作り、Kafka Connect は `topic.creation` で出力先を作る |
| Kafka | Toxiproxy 専用のリスナー `TOXI_HOST`(advertised `localhost:19094`)と `TOXI_INTERNAL`(advertised `toxiproxy:19095`) | メタデータの取得後の接続も Toxiproxy を通るようにする。ホストのテスト(P14)とコンテナ内のサービスの両方で障害を注入できる |
| Kafka Connect | 内部トピックは `_connect.*`、Consumer Group は `debezium.connect`、Apicurio の Converter を有効化 | 内部トピックを業務トピックと区別する。P06 で Avro + Apicurio を使う |
| Apicurio | 既定のグローバルルール `apicurio.rules.global.compatibility=FULL_TRANSITIVE`(と `validity=FULL`)、ストレージは PostgreSQL | ADR-0014 の「登録時は全版と FULL」を、アーティファクトごとの設定を忘れても効くよう既定で強制する。再起動でスキーマ ID が変わらないよう永続化する |
| Keycloak | realm `eiaf`、スコープ `sales.order:read` / `sales.order:write`(Framework 12.3)、`aud` に `order-api`、`iss` を `http://localhost:19180/realms/eiaf` に固定(`KC_HOSTNAME`)し、コンテナ内からのバックチャネルは `keycloak:8080` | ホストとコンテナのどちらでトークンを取っても `iss` が同じになり、JWT の `iss` 検証(CLAUDE.md §5)が環境で揺れない |
| PostgreSQL | `wal_level=logical`、Debezium 用の `debezium` ユーザー(REPLICATION と CONNECT のみ) | CDC(P06)。テーブルの SELECT とパブリケーションは、テーブルの所有者がマイグレーションで付与する |

- ADR-0014 は Apicurio の互換性ルールを「トピック単位」としていたが、上のとおりグローバルの既定ルールで全アーティファクトに FULL_TRANSITIVE を適用する。アーティファクト単位で緩める場合は ADR を書く。

### 8. secure profile は P03 の範囲から外す
- ADR-0008 の「Kafka の SSL / ACL は任意の `secure` profile」は P03 では作らない。範囲を絞るためで、フォローアップの Issue #26 で扱う(開発用 CA の生成、SASL_SSL リスナー、ACL、verify、CI)。
- それまでの Kafka はすべて PLAINTEXT で、127.0.0.1 にだけ公開する。

## Alternatives Considered
- **profile ごとに compose ファイルを分けて `include` で組み合わせる**: ファイルごとの見通しは良いが、core のサービスへの依存と共通の設定(ヘルスチェックのアンカーなど)がファイルをまたぐ。1 ファイル + profiles のほうが `docker compose config` での検査も単純。不採用。
- **profile を独立させる(core を含めずに起動できる)**: Compose は無効な profile のサービスへの `depends_on` を解決できず、各 profile に Kafka や PostgreSQL を重複して持つことになる。不採用。
- **ヘルスチェック用に独自のイメージを作る(ベースに curl を追加する)**: 手段が確実になる代わりに、ビルド・公開・ダイジェストの管理が増え、上流のイメージの更新に追従しにくい。既存の手段で全イメージをまかなえたので不採用。
- **イメージをタグだけで固定する**: タグは付け替えられることがあり、同じタグでも中身が変わりうる。不採用。
- **Testcontainers 側で版を別に持つ(`libs.versions.toml` など)**: compose との二重管理になり、ずれる。不採用。
- **ホストのポートを各製品の既定値のままにする**: 開発者の手元で動いている他のツール(PostgreSQL 5432、Kafka 9092 など)と衝突する。不採用。

## Consequences(トレードオフ)
- 積み上げ方式のため、`chaos` だけを使う場合も core(実測で約 1.8GB)が起動する。
- イメージを更新するたびにダイジェストと対応アーキテクチャを確認する手間がかかる。代わりに、開発機・CI・統合テストで同じイメージが使われる。
- image マウントを使うため、古い Docker Engine では otel-collector と loki が healthy にならない。README に動作を確認した版を書く。
- PostgreSQL の初期化スクリプト(`postgres/init/`)はデータディレクトリが空のときだけ実行される。スクリプトを変えたら `make clean`(ボリュームの削除)が必要になる。
- secure profile を後回しにしたため、Framework 12.3 のトピック単位の ACL は Issue #26 まで検証できない。
