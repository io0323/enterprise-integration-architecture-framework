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
- 例外: Kafka Connect(Debezium)は公開イメージを使わず、`images.env` の `KAFKA_IMAGE` から組み立てる(§9)。

### 3. SFTP は linuxserver/openssh-server を使う
- 当初の候補の `atmoz/sftp` は、2026-09 時点でも amd64 だけのイメージしか公開していない。`emberstack/sftp` は 2024 年以降更新がない。
- マルチアーキテクチャで更新が続いている `linuxserver/openssh-server` を使い、公開鍵認証だけを許可する。社内向け(`sftp`、file profile)と取引先向け(`sftp-b2b`、b2b profile)で、利用者・鍵・保存領域を分ける。

### 4. ヘルスチェックは既存の機能だけで行い、独自のイメージは作らない
Docker のヘルスチェックはコンテナの中で実行されるため、イメージ内で実行できる手段が要る。次の順で選び、**ヘルスチェックのために独自のイメージは作らない**(Kafka Connect を組み立てる §9 は、ヘルスチェックとは別の理由による例外)。

| 手段 | 対象 |
|---|---|
| イメージに含まれるツール(`curl` / `wget` / `pg_isready` / `mosquitto_sub` / `nc` / Kafka の CLI) | kafka, kafka-connect(ベースの apache/kafka の BusyBox の `wget`), apicurio, postgres, prometheus, grafana, mosquitto, seaweedfs, sftp |
| イメージに含まれる本体の機能 | tempo(`/tempo -health`: `/ready` を確認して終了する)、toxiproxy(`/toxiproxy-cli list`: API に問い合わせる) |
| bash の `/dev/tcp` で HTTP を送る | keycloak(管理ポートの `/health/ready`)、apisix(`/status/ready`)。どちらも bash はあるが HTTP クライアントがない |
| 既存の公開イメージ `busybox`(静的リンク)を読み取り専用で `/probe` にマウントし、その `wget` を使う | otel-collector(`health_check` 拡張の `:13133`)、loki(`/ready`)。どちらもシェルも HTTP クライアントもない |

- busybox は `images.env` の `PROBE_IMAGE` に、ほかのイメージと同じくタグとダイジェストで固定する。マウントは Docker の image マウント(`volumes: [{type: image, source: ..., target: /probe}]`)で行う。動作は Docker Engine 29.8.0 / Compose v5.5.1(ローカル)と Docker Engine 28.0.4 / Compose v2.38.2(CI の ubuntu-latest)で確認した。README には確認した最も古い組み合わせを最低バージョンとして書く。古い Docker Engine は image マウントに対応していない。
- 外からの確認(`verify.sh` から HTTP で叩く)だけにする案は、`up --wait` と `depends_on: condition: service_healthy` が使えなくなるため採らない。外からの確認は、ヘルスチェックに加えて `verify.sh` で行う。

### 5. Testcontainers も images.env を読む(実装は P04a)
- P04a 以降の統合テスト(Testcontainers)は、compose と同じ `infra/local/images.env` のイメージを使う。版がずれると、ローカル基盤で確かめたことと統合テストの結果が食い違うため。
- 方針:
  - `build-logic` の JVM の convention(`eia.jvm-library` / `eia.jvm-service`)で、`Test` タスク(`integrationTest` を含む)に `infra/local/images.env` を入力ファイルとして宣言し(変更時にテストを再実行させる)、パスをシステムプロパティ `eia.images.file` で渡す。
  - テスト用の小さなヘルパー(例 `InfraImages.get("KAFKA_IMAGE"): DockerImageName`)が `KEY=VALUE` の行を読み、コメントと空行を無視して `DockerImageName.parse` する。見つからないキーは例外にする(黙って `latest` にしない)。
  - Testcontainers のモジュールが特定のイメージ名を要求する場合(例 `KafkaContainer`)は `asCompatibleSubstituteFor` で互換を宣言する。
- ヘルパーは **テスト専用の JVM モジュール `platform/test-support`**(`io.eia.platform.testsupport.InfraImages`)に置く(P04a で決定)。
  - `build-logic` に置かない理由: convention plugin のクラスはテストの実行時クラスパスに載らない。テストから使うには、別途ライブラリとして公開する必要がある。
  - 各モジュールは `testImplementation` / `integrationTestImplementation` からだけ参照する。本番コードに Testcontainers が入らないよう、次の 2 つで検査する。
    - ソースの参照: Konsist の `testSupportOnlyFromTests` で、テスト以外のソースセットからの import と完全修飾名での参照を禁止する。
    - Gradle の依存宣言: `eia.jvm-library` の `verifyNoTestSupportInMain`(`check` に含まれる)で、`main` の `compileClasspath` / `runtimeClasspath` に `:platform:test-support` が推移的にも含まれないことを検査する。
  - パスは、相対パスで入力に宣言した `CommandLineArgumentProvider` で渡す(`io.eia.buildlogic.InfraImagesArgument`)。`systemProperty` で絶対パスを渡すと、パスがビルドキャッシュのキーに入り、マシン間でキャッシュが効かなくなるため。
  - Testcontainers の `DockerImageName` はタグとダイジェストの併記を解釈できない。そのため、タグを落としてダイジェストだけで固定する(`apache/kafka@sha256:...`)。
- CI: `ci.yml` の `integration` ジョブで `./gradlew integrationTest` を実行する。対象の変更は `platform/**`、`shared/**`(統合テストが使う kernel などを含む)、`services/*/adapters/**`、`**/src/integrationTest/**`(P05 以降にサービスやツールへ置く統合テスト)、`infra/local/images.env`、`build-logic/**`、`gradle/libs.versions.toml`、`ci.yml` で、テストの件数は Step Summary に出す。

### 6. ホスト側のポートと秘密情報
- ホスト側のポートは **127.0.0.1 の 19000〜19999** に割り当てる(他の開発ツールの既定ポートとの衝突を避け、LAN には公開しない)。一覧は `infra/local/README.md`。
- 秘密情報(DB・Keycloak・Grafana・MQTT・S3 の資格情報、SFTP の鍵)は `make env`(`scripts/init-env.sh`)がランダムに生成し、`infra/local/.env` と `infra/local/secrets/` に置く(どちらも .gitignore 済み)。`env.example` には変数名だけを書く(`.env.*` の形の名前にしない。Claude Code の deny `Read(**/.env.*)` に当たって読めなくなるため)。compose は `${VAR:?}` で必須にし、未生成なら起動しない。
- PostgreSQL はサービスごとに DB とユーザーを分け(Shared Database 禁止)、他のサービスの DB には接続できない。
- **S3(SeaweedFS)の資格情報は現在 1 つ(Admin 権限の `eiaf`)で、P04a で用途別に分ける。** P03 の時点ではバケットがまだ決まっていないため。P04a で audit 専用の資格情報を作り、audit 用のバケットだけを操作できるポリシーを付ける。file-exchange(P09)と b2b-gateway(P12)の資格情報も、それぞれのフェーズで同じ方式で分ける(Framework 12.3 の最小権限、12.4 のアンチパターン「全消費者共有の 1 クレデンシャル」)。記録: #6。

### 7. 後続フェーズの前提になる設定
| 対象 | 設定 | 理由 |
|---|---|---|
| Kafka | `auto.create.topics.enable=false` | 命名規約(Framework 6.2)外のトピックが暗黙に作られるのを防ぐ。トピックは各フェーズの初期化で明示的に作り、Kafka Connect は `topic.creation` で出力先を作る |
| Kafka | Toxiproxy 専用のリスナー `TOXI_HOST`(advertised `localhost:19094`)と `TOXI_INTERNAL`(advertised `toxiproxy:19095`) | メタデータの取得後の接続も Toxiproxy を通るようにする。ホストのテスト(P14)とコンテナ内のサービスの両方で障害を注入できる |
| Kafka Connect | 内部トピックは `_connect.*`、Consumer Group は `debezium.connect`、Apicurio の Converter を有効化 | 内部トピックを業務トピックと区別する。P06 で Avro + Apicurio を使う |
| Kafka Connect | `config.providers=env`(`EnvVarConfigProvider`)。コネクタの設定の秘密情報は `${env:DEBEZIUM_DB_PASSWORD}` のように参照で書き、値はコンテナの環境変数(`.env`)から渡す | 平文で書くと `_connect.configs` トピックと REST(`GET /connectors/{name}/config`)から読めてしまう(Framework 12.2)。`make verify PROFILE=cdc` で平文が現れないことを検査する。P06 以降のコネクタも同じ書き方にする |
| Apicurio | 既定のグローバルルール `apicurio.rules.global.compatibility=FULL_TRANSITIVE`(と `validity=FULL`)、ストレージは PostgreSQL | ADR-0014 の「登録時は全版と FULL」を、アーティファクトごとの設定を忘れても効くよう既定で強制する。再起動でスキーマ ID が変わらないよう永続化する |
| Keycloak | realm `eiaf`、スコープ `sales.order:read` / `sales.order:write`(Framework 12.3)、`aud` に `order-api`、`iss` を `http://localhost:19180/realms/eiaf` に固定(`KC_HOSTNAME`)し、コンテナ内からのバックチャネルは `keycloak:8080` | ホストとコンテナのどちらでトークンを取っても `iss` が同じになり、JWT の `iss` 検証(CLAUDE.md §5)が環境で揺れない |
| PostgreSQL | `wal_level=logical`、Debezium 用の `debezium` ユーザー(REPLICATION と CONNECT のみ) | CDC(P06)。テーブルの SELECT とパブリケーションは、テーブルの所有者がマイグレーションで付与する |

- ADR-0014 は Apicurio の互換性ルールを「トピック単位」としていたが、上のとおりグローバルの既定ルールで全アーティファクトに FULL_TRANSITIVE を適用する。アーティファクト単位で緩める場合は ADR を書く。

### 8. secure profile は P03 の範囲から外す
- ADR-0008 の「Kafka の SSL / ACL は任意の `secure` profile」は P03 では作らない。範囲を絞るためで、フォローアップの Issue #26 で扱う(開発用 CA の生成、SASL_SSL リスナー、ACL、verify、CI)。
- それまでの Kafka はすべて PLAINTEXT で、127.0.0.1 にだけ公開する。

### 9. 例外: Kafka Connect のイメージは自分たちで組み立てる
- **理由**: Debezium の公式イメージ(`quay.io/debezium/connect`)は、ダイジェストで固定できない。
  - 最新のパッチ版のタグ(`3.6.3.Final`)が、毎日別のダイジェストで付け直される(2026-09-19 以降の quay.io のタグの履歴で確認。理由は公表されていない)。
  - 付け直される前のダイジェストは quay.io から削除され、取得できなくなる(`manifest unknown`)。2026-09-27 に main と PR の `verify (cdc)` がこれで失敗した(PR #34)。
  - そのため §2(タグとダイジェストで固定し、開発機・CI・統合テストで同じイメージを使う)を満たせない。
- **方式**: `images.env` で固定済みの `apache/kafka`(`KAFKA_IMAGE`)に、Maven Central の成果物を載せる(`infra/local/images/kafka-connect/Dockerfile`)。
  - Debezium の Postgres コネクタ(`io.debezium:debezium-connector-postgres:<版>:plugin@tar.gz`)と、Apicurio の Converter(`io.apicurio:apicurio-registry-distro-connect-converter:<版>@tar.gz`。版は Apicurio Registry のサーバに合わせる)。
  - 版と SHA-256 は Dockerfile の `ARG` で固定し、`ADD --checksum=sha256:...` で取得する(一致しなければビルドが失敗する)。Maven Central は公開した成果物を差し替えられないため、版と SHA-256 の固定は崩れない。
  - ベースイメージは build 引数 `KAFKA_IMAGE` で受け取る(既定値なし)。compose は `build.args` で `images.env` の値を渡す。
  - CI(`compose-config`)で、`infra/local/images/*/Dockerfile` の `FROM` が `images.env` の変数だけであること、URL から取得する `ADD` に `--checksum` があることを検査する。
  - `make up` は `--build` を付けて、Dockerfile の変更を反映する(変更がなければキャッシュを使う)。
- **設定の再現**: 公式イメージのエントリポイントと同じ規則で、`CONNECT_<NAME>` の環境変数を `connect-distributed.properties` の `<name>` にする(`connect-entrypoint.sh`)。既定値(Converter、flush の間隔、`rest.advertised.*` など)も公式イメージの実効値に合わせた。
  - 違いは 2 点: 待ち受けを `listeners=http://0.0.0.0:8083` で指定する(Kafka 4 で `rest.host.name` / `rest.port` は廃止)。`plugin.path` は `/opt/kafka/plugins`。
  - `EnvVarConfigProvider`(`config.providers=env`)は §7 のとおり。`make verify PROFILE=cdc` の全項目(平文のパスワードが返らないことを含む)で確認した。
  - 公式イメージにあってこのイメージにないもの: Postgres 以外のコネクタ、Debezium の scripting / OpenTelemetry / Jolokia / JMX exporter の拡張、`LOG_LEVEL` などの独自の環境変数。必要になったフェーズで、同じ方式(Maven Central の成果物を SHA-256 で固定)で追加する。
- **Testcontainers(P06 以降の方針)**: 統合テストも同じ Dockerfile から組み立てる。`ImageFromDockerfile` に `infra/local/images/kafka-connect/` を渡し、build 引数 `KAFKA_IMAGE` に `InfraImages`(§5)が返すダイジェスト固定の名前を渡す。版とチェックサムを Dockerfile の 1 か所に保ち、compose と統合テストでずれないようにする。
- **版の更新**: Dockerfile の `ARG`(版と SHA-256)を書き換える。SHA-256 は Maven Central の `.sha256` と、取得したファイルの値の両方で確かめる(手順は `infra/local/README.md`)。

## Alternatives Considered
- **profile ごとに compose ファイルを分けて `include` で組み合わせる**: ファイルごとの見通しは良いが、core のサービスへの依存と共通の設定(ヘルスチェックのアンカーなど)がファイルをまたぐ。1 ファイル + profiles のほうが `docker compose config` での検査も単純。不採用。
- **profile を独立させる(core を含めずに起動できる)**: Compose は無効な profile のサービスへの `depends_on` を解決できず、各 profile に Kafka や PostgreSQL を重複して持つことになる。不採用。
- **ヘルスチェック用に独自のイメージを作る(ベースに curl を追加する)**: 手段が確実になる代わりに、ビルド・公開・ダイジェストの管理が増え、上流のイメージの更新に追従しにくい。既存の手段で全イメージをまかなえたので不採用。
- **イメージをタグだけで固定する**: タグは付け替えられることがあり、同じタグでも中身が変わりうる。不採用。
- **Debezium の公式イメージの扱い(§9)**:
  - 付け直しが止まった前のパッチ版に固定する: 次のパッチ版が出ると、今の版も毎日付け直されうる。止まる保証がない。不採用。
  - 自前のレジストリにミラーしてから固定する: ミラーの運用(認証・保存・更新)が増える。ローカル参照実装の範囲を超える。不採用。
  - タグだけで固定する: 開発機・CI・統合テストで中身が日ごとに変わりうる。§2 の目的に反する。不採用。
  - 毎日ダイジェストを更新する PR を自動で作る: 毎日の PR と CI が発生し、main が壊れる時間も残る。不採用。
- **Testcontainers 側で版を別に持つ(`libs.versions.toml` など)**: compose との二重管理になり、ずれる。不採用。
- **ホストのポートを各製品の既定値のままにする**: 開発者の手元で動いている他のツール(PostgreSQL 5432、Kafka 9092 など)と衝突する。不採用。

## Consequences(トレードオフ)
- 積み上げ方式のため、`chaos` だけを使う場合も core(実測で約 1.8GB)が起動する。
- イメージを更新するたびにダイジェストと対応アーキテクチャを確認する手間がかかる。代わりに、開発機・CI・統合テストで同じイメージが使われる。
- image マウントを使うため、古い Docker Engine では otel-collector と loki が healthy にならない。README に動作を確認した版を書く。
- PostgreSQL の初期化スクリプト(`postgres/init/`)はデータディレクトリが空のときだけ実行される。スクリプトを変えたら `make clean`(ボリュームの削除)が必要になる。
- secure profile を後回しにしたため、Framework 12.3 のトピック単位の ACL は Issue #26 まで検証できない。
- Kafka Connect のイメージを組み立てるため(§9)、`make up PROFILE=cdc` の初回は Maven Central からの取得とビルドの時間がかかる。コネクタの版の更新は、公式イメージの追従ではなく自分たちの作業になる。公式イメージの拡張(Postgres 以外のコネクタなど)は、必要な分を自分たちで足す。

## 改訂履歴
- 2026-09-26: §5 のヘルパーの置き場所を `platform/test-support` に決め、CI の `integration` ジョブを追加した(P04a ①)。
- 2026-09-26: 変数名の例のファイルを `infra/local/.env.example` から `infra/local/env.example` に改名した。`.claude/settings.json` の deny は allow より優先され、`Read(**/.env.*)` から例のファイルだけを除外できないため、秘密情報の `.env.*` をすべて deny にしたうえで例のファイルを読めるようにするには、名前を変えるしかない(P04a ① のレビュー Major 1)。
- 2026-09-27: Debezium Connect(`quay.io/debezium/connect:3.6.3.Final`)のダイジェストを `sha256:a41a03c0…` に更新した。上流でタグが付け直され、固定していた `sha256:c51aab05…` が quay.io から削除されて `manifest unknown` になり、`verify (cdc)` が失敗したため。新しいダイジェストが linux/amd64 と linux/arm64 を含むことは `docker buildx imagetools inspect` で確認した(§2)。ダイジェストで固定していても、上流が削除すると取得できなくなる点は §2 の運用(更新手順)で扱う。
  - タグが付け直された理由: **理由不明**。Debezium のリリースノート・ブログ・`debezium/container-images` の README とワークフローには、リリース済みのタグを作り直す旨の記載が見つからなかった(2026-09-27 に確認)。
  - quay.io のタグの履歴(`/api/v1/repository/debezium/connect/tag/?specificTag=3.6.3.Final&onlyActiveTags=false`)から分かった事実:
    - `3.6.3.Final` は、少なくとも 2026-09-19 から**毎日 01:00〜02:30 UTC ごろに別のダイジェストで付け直されている**。
    - 付け直される前のダイジェストは、確認したものすべて(過去 5 日分)が取得できない(`manifest unknown`)。
    - 前のパッチ版の `3.6.2.Final` も 2026-09-18 まで同じように毎日付け直され、それ以降は変わっていない(`3.6.3.Final` が出た時期と重なる)。
  - このため、最新のパッチ版をダイジェストで固定すると、およそ 1 日で取得できなくなる見込みである。固定の方法の見直しと、ダイジェストを取得できるかの定期確認は #35 で扱う。
- 2026-09-27: §9 を追加した。Debezium の公式イメージは最新のパッチ版のタグが毎日付け直され、古いダイジェストが取得できなくなるため(PR #34・#36)、Kafka Connect のイメージを `apache/kafka` と Maven Central の成果物(版と SHA-256 で固定)から組み立てる。`images.env` から `DEBEZIUM_CONNECT_IMAGE` を削除した。§2・§4 に例外を記載し、Testcontainers での使い方(P06 以降の方針)を §9 に記載した。
