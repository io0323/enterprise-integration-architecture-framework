# Implementation Roadmap

各フェーズ = GitHub Milestone。フェーズ内の各項目 = Issue。1 フェーズ 1 PR を原則とする。
サンプル業務ドメイン: **EC 受注 → 在庫引当 → 決済 → 出荷**(Framework 1.6 のシナリオ)。
構成の改訂履歴: ADR-0009(P00 キックオフレビュー)。

| Phase | 名称 | 主な Framework 章 |
|---|---|---|
| P00 | Repository Bootstrap | 16, 19 |
| P01 | Shared Kernel & Canonical Model (KMP) | 15 |
| P02 | Contracts & Governance CI | 5, 6, 15, 16 |
| P03 | Local Infrastructure | 2, 17 |
| P04a | Platform: Observability, Security & Audit | 12, 14 |
| P04b | Platform: Resilience | 13 |
| P05 | API Integration: order-service | 5, 12 |
| P06 | Outbox & CDC | 8 |
| P07 | Event Integration + Saga | 4, 6, 13 |
| P08 | Batch & ELT/ETL | 7 |
| P09 | File Integration | 9 |
| P10 | SaaS / Webhook / iPaaS-like Flow | 10 |
| P11 | IoT (MQTT) & KMP SDK | 6, 17.3 |
| P12 | B2B / EDI Gateway | 11 |
| P13 | gRPC & GraphQL BFF | 3, 5, 12 |
| P14 | Resilience, E2E & SLO | 13, 14, 18 |

共通ルール:
- `tests/e2e` は P05 で骨組みを作り、以降の各フェーズで DoD のシナリオを追加する(P14 はまとめ)。
- DoD の証跡は PR 本文に記載し、計測を伴うものは `docs/reports/`、運用手順は `docs/runbooks/` に残す。

---

## P00 Repository Bootstrap
- Gradle Wrapper、settings.gradle.kts、`gradle/libs.versions.toml`、`build-logic`(`eia.kmp-library` / `eia.kmp-domain` / `eia.jvm-library` / `eia.jvm-service` / `eia.quality`)
- `eia.kmp-library` / `eia.kmp-domain` はターゲット集合を DSL で宣言できるようにする。`eia.kmp-domain` の既定は jvm のみで、js / native を 1 行で追加できること(ADR-0004)
- ktlint・detekt・Kover・kotest 設定、`integrationTest` ソースセット規約
- Konsist によるアーキテクチャテスト(`tools/architecture-test`): 依存方向、commonMain の禁止 import、`kotlin.Result` の使用禁止(ADR-0004)
- `.editorconfig`、`.gitignore`、`Makefile`、GitHub Actions CI(build + test + cache。macOS ジョブは `shared/**` 変更時のみ)
- **DoD**: 空モジュール群で `./gradlew build` が CI 上で成功する。Konsist が依存方向違反・禁止 import・`kotlin.Result` の使用を検出するテストを持つ。kotest の空テストが jvm / js / linuxX64 で通る。

## P01 Shared Kernel & Canonical Model (KMP)
- `shared/kernel`: `Result`/`DomainError` 階層(Retryable / NonRetryable)、`catching {}`、`CorrelationId`、`IdempotencyKey`、`RetryPolicy`(Exponential Backoff + Jitter の純粋計算)、`Clock`、`Money`/`Currency`
- `shared/canonical-model`: ドメイン単位のパッケージ(sales / catalog / billing / logistics)で Customer / Product / Order / OrderLine / Invoice / Shipment を定義する(kotlinx.serialization、UTC、通貨明示)
- targets: jvm, js(IR), linuxX64, macosArm64(ADR-0004)
- **DoD**: jvm / js / linuxX64 の commonTest が ubuntu CI で成功し、macosArm64 は macOS ジョブかローカル実行の証跡で成功を示す。フレームワーク依存ゼロ(Konsist)。
- **DoD**: Kover の閾値(domain / application 90%、全体 75%)の強制を有効にし(`gradle.properties` の `eia.kover.enforce=true`。P00 では閾値の設定のみ)、`./gradlew build` が成功する。

## P02 Contracts & Governance CI
- `contracts/openapi/order-api.v1.yaml`(OpenAPI 3.1。`servers` は `/{domain}`、`paths` は `/v{n}/`: ADR-0005)、`contracts/asyncapi/order-events.v1.yaml`(AsyncAPI 3.0)、`contracts/avro/*.avsc`
- `contracts/files/manifest.v1.schema.json`(件数・SHA-256・スキーマ版・traceparent・correlationId)
- `contracts/catalog/*.yaml`: 連携カタログ(schema は INTEGRATION_STANDARDS.md 参照)
- `tools/contract-check`: 命名規約 lint(Topic / `cmd-` コマンドトピック / API パス / ファイル)、Avro BACKWARD 互換検査(main との差分。オフラインで実行)、OpenAPI 破壊的変更検知、カタログ必須項目検証、コマンドトピックの購読者が 1 つであることの検査(ADR-0006)
- Canonical Model(Kotlin)と Avro スキーマの項目一致を検査する JVM テスト(ADR-0004)
- GitHub Actions `contract-check.yml`(contracts/** 変更時)
- **DoD**: 意図的な破壊的変更・命名違反・Owner 欠落・Canonical と Avro の不一致のサンプルで CI が失敗することをテストで示す。

## P03 Local Infrastructure
- `infra/local/docker-compose.yml`: Kafka(KRaft)、Kafka Connect + Debezium、Apicurio、PostgreSQL(サービス別 DB)、Keycloak(realm import)、APISIX、Mosquitto、MinIO、SFTP、OTel Collector、Prometheus、Grafana、Tempo、Loki、Toxiproxy
- profiles: `core`(kafka, registry, postgres, keycloak, apisix, otel 一式)/ `cdc` / `iot` / `file` / `b2b` / `chaos` / `secure`(Kafka の SSL・ACL)。メモリ 16GB の開発マシンで `core` が動くようリソース制限を設定する
- Toxiproxy 経由で Kafka に接続するための専用リスナー(advertised listeners)を用意する
- Kafka・Debezium・Apicurio などのイメージは最新の安定版を確認して固定する
- `make up/down/logs/ps`、ヘルスチェック、Grafana データソース provisioning、ポート一覧(`infra/local/README.md`)
- **DoD**: profile ごとに `make up PROFILE=<name>` で全コンテナが healthy になる。README に起動手順とポート一覧がある。

## P04a Platform: Observability, Security & Audit
- `platform/observability`: OTel 初期化、Ktor プラグイン(traceparent / X-Correlation-Id 伝搬。生成と解析は `shared/resilience` を使用)、構造化 JSON ログ(MDC)、マスキングユーティリティ
- `platform/security`: Ktor JWT 検証(JWKS, iss/aud/exp)、スコープ認可、Client Credentials トークン取得クライアント、`SecretProvider` Port と環境変数による実装(ADR-0008)
- `platform/audit`: 追記専用テーブル + ハッシュチェーン、MinIO(Object Lock)への日次アンカー保存(ADR-0008)
- **DoD**: 各部品に単体テストと Testcontainers 統合テストがある。改竄したレコードをハッシュチェーンの検証が検出する。不正な iss / aud / exp の JWT を拒否する。

## P04b Platform: Resilience
- `shared/resilience`(KMP): Timeout / Retry / Circuit Breaker / Bulkhead / Fallback(coroutines ベース。kernel の RetryPolicy を利用し、Clock と Random をインジェクション)(ADR-0004)
- `platform/reliability`(JVM): Ktor Client への結線、メトリクス(OTel)出力
- **DoD**: `shared/resilience` の commonTest が jvm / js / linuxX64 で成功する。Toxiproxy で Circuit Breaker の Closed → Open → Half-Open → Closed の遷移を統合テストで検証する。

## P05 API Integration: order-service
- Clean Architecture 4 モジュール構成。`POST /v1/orders`(Idempotency-Key 必須)、`GET /v1/orders/{id}`。Gateway 公開パスは `/sales/v1/orders`(ADR-0005)
- APISIX 経由公開(JWT 検証・Rate Limit・Correlation ID 付与・prefix 書き換え)、Keycloak Client Credentials
- mTLS(APISIX → order-service。開発用 CA はスクリプトで生成し、コミットしない)(ADR-0008)
- エラーは RFC 9457 Problem Details
- 最低限の RED ダッシュボード(Grafana)
- `tests/e2e` の骨組み(`:tests:e2e`、`make e2e`)と P05 のシナリオ
- **DoD**: 同一 Idempotency-Key の再送で同一レスポンスが返る。429 に Retry-After が付く。mTLS なしの直接接続を拒否する。トレースが Tempo で、RED がダッシュボードで確認できる。

## P06 Outbox & CDC
- `platform/outbox`: 業務更新と同一 Tx で outbox に挿入し、同じ Tx で削除する(既定)。保持期間パターン(日次パーティション + DROP ジョブ)も提供する(ADR-0007)
- `platform/messaging-kafka`(Producer 側): CloudEvents ヘッダ、Avro Serde(Apicurio。スキーマ ID はペイロード埋め込み)
- Debezium Outbox Event Router + ByteArrayConverter で `sales.order.created.v1` へ発行する(ADR-0007)
- `legacy-sim`(レガシー DB を模擬)→ Debezium CDC → Anti-Corruption 変換 → 整形済みトピック
- Snapshot + Incremental、整合性チェックジョブ(件数/ハッシュ)
- Runbook: `docs/runbooks/cdc-outbox-lag.md`、`docs/runbooks/cdc-resync.md`
- **DoD**: Kafka を止めたままアプリを動かしても、復旧後にイベントが欠損なく発行される(統合テスト)。outbox テーブルに行が残らない。replication slot の遅延がアラートになる。

## P07 Event Integration + Saga
- inventory / payment / shipping サービス(各 Consumer Group)
- `platform/messaging-kafka`(Consumer 側): 型付き Consumer、冪等消費(processed_message)、リトライ → DLQ(エラーメタデータのヘッダ付き)、Replay CLI(フィルタ・件数上限・dry-run)
- 注文 Saga(Orchestration 方式、補償を含む)。状態は saga テーブルで永続化し、状態遷移表を Mermaid stateDiagram で docs に記載する
- コマンドは `{domain}.{entity}.cmd-{command}.v{n}` トピックで送る(ADR-0006)
- Runbook: `docs/runbooks/event-dlq-replay.md`
- **DoD**: 在庫不足・決済失敗・タイムアウトで補償が走る E2E テストがある。Poison Message が DLQ に隔離され、本流が止まらない。Replay で DLQ から再処理できる。

## P08 Batch & ELT/ETL
- `platform/batch`: 軽量 DAG ランナー(Kotlin DSL、前段の完了イベント・ファイル到着での起動、Checkpoint、SLA 遅延予測のメトリクス)(ADR-0008)
- `batch-etl` ジョブ: 受注 DB → 分析 DB(Postgres を DWH と見立てる)
  - ELT: raw テーブルへの Incremental Load(Watermark + Checkpoint)→ SQL で変換(既定。Framework 7.1)
  - ETL: 機密項目のマスキングが必要なデータセットはロード前に変換する
  - いずれも Upsert / パーティション置換で再実行安全にする
- 週次 Full 照合ジョブ、manifest・ジョブ属性での traceparent 引き継ぎ
- Runbook: `docs/runbooks/batch-rerun.md`
- **DoD**: ジョブを途中で kill して再実行しても、重複・欠損がゼロ。SLA 遅延予測アラートが発火するシナリオを示す。

## P09 File Integration
- 日次売上ファイル出力: Parquet / CSV、gzip、SHA-256 manifest(`contracts/files` のスキーマに準拠)、一時名 → リネーム
- MinIO / SFTP 送信、受信側の検証(checksum・件数・スキーマ版)、PGP 暗号化オプション
- Parquet の書き出し手段(ライブラリ)を比較して ADR に残す
- **DoD**: 改竄・欠損ファイルを受信側が拒否するテストがある。manifest の traceparent でトレースがつながる。

## P10 SaaS / Webhook / iPaaS-like Flow
- `saas-mock`(CRM を模擬。ページング・レート制限あり)
- Webhook Receiver: HMAC 署名検証 → 冪等化 → 社内 Event Bus へ変換して発行
- `integration-flow` モジュール: 宣言的 Flow(YAML: trigger → map → route → deliver)の最小実装、Canonical 経由のマッピング。Flow 定義の JSON Schema を用意し、定義ミスを起動時に検出する
- **DoD**: 署名不正を拒否する。SaaS の 429 で Backoff する。Flow 定義だけで新しいマッピングを追加できる。

## P11 IoT (MQTT) & KMP SDK
- `shared/integration-sdk`: Ktor Client ベースの KMP SDK(認証・`shared/resilience` によるリトライ・Correlation ID / traceparent)。エンジンはプラットフォーム別(CIO / JS / Darwin / Curl)
- `tools/device-simulator`: Kotlin/Native のデバイスシミュレータ CLI。MQTT v5 クライアント(ライブラリ採用か ktor-network での最小実装か)を ADR で決める
- Mosquitto → MQTT-Kafka Bridge(QoS1、デバイス ID をキー)→ `iot.telemetry.reported.v1`
- **DoD**: native バイナリのシミュレータから送信したテレメトリが Kafka に到達し、MQTT v5 のユーザープロパティで運んだ traceparent でトレースできる。

## P12 B2B / EDI Gateway
- `b2b-gateway`: Trading Partner 台帳、SFTP 受信(AS2 は範囲外: ADR-0008)、EDIFACT ORDERS(サブセット。仕様は `contracts/files/edi/`)パーサ、3 段 Validation、Canonical 変換、CONTRL 相当の受領応答
- 原本保管(MinIO, Object Lock)と `platform/audit` への記録
- **DoD**: 構文・スキーマ・業務のそれぞれのエラーで、正しいエラー応答と運用通知が出る。受信した全ドキュメントが Audit に記録され、ハッシュチェーンの検証が通る。

## P13 gRPC & GraphQL BFF
- inventory の在庫照会を gRPC(内部)で公開する。proto は contracts/proto、mTLS 必須(ADR-0008)
- `bff-graphql`: 注文 + 在庫 + 出荷の集約クエリ(クエリ深さ・複雑度の制限)。GraphQL ライブラリは ADR で選ぶ
- **DoD**: 契約から生成したスタブで通信する。mTLS なしの gRPC 接続を拒否する。GraphQL の過大なクエリを拒否する。

## P14 Resilience, E2E & SLO
- Toxiproxy による障害注入シナリオ(遅延・切断・Broker 停止)を `tests/e2e` に集約する
- Grafana ダッシュボード(RED、Consumer Lag、DLQ、Batch SLA、replication slot 遅延)、SLO バーンレートアラート
- Tier 別 NFR(Framework 18 章)の計測レポート(`docs/reports/p14-nfr.md`)。ADR-0008 で範囲外にした項目も明記する
- 障害シナリオごとに「期待挙動 / 観測方法 / 結果」を `docs/reports/p14-resilience.md` に記録する
- **DoD**: 全シナリオが自動実行でき、ダッシュボードで可視化される。全アラートが Runbook に紐づいている。
