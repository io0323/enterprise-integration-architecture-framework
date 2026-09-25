# Implementation Roadmap

各フェーズ = GitHub Milestone。フェーズ内の各項目 = Issue。1 フェーズ 1 PR を原則とする。
サンプル業務ドメイン: **EC 受注 → 在庫引当 → 決済 → 出荷**(Framework 1.6 のシナリオ)。

| Phase | 名称 | 主な Framework 章 |
|---|---|---|
| P00 | Repository Bootstrap | 16, 19 |
| P01 | Shared Kernel & Canonical Model (KMP) | 15 |
| P02 | Contracts & Governance CI | 5, 6, 15, 16 |
| P03 | Local Infrastructure | 2, 17 |
| P04 | Platform Libraries | 12, 13, 14 |
| P05 | API Integration: order-service | 5 |
| P06 | Outbox & CDC | 8 |
| P07 | Event Integration: inventory / payment / shipping + Saga | 4, 6, 13 |
| P08 | Batch & ETL | 7 |
| P09 | File Integration | 9 |
| P10 | SaaS / Webhook / iPaaS-like Flow | 10 |
| P11 | IoT (MQTT) & KMP SDK | 6, 17.3 |
| P12 | B2B / EDI Gateway | 11 |
| P13 | gRPC & GraphQL BFF | 3, 5 |
| P14 | Resilience, E2E & SLO | 13, 14, 18 |

---

## P00 Repository Bootstrap
- Gradle Wrapper、settings.gradle.kts、`gradle/libs.versions.toml`、`build-logic`(`eia.kmp-library` / `eia.jvm-library` / `eia.jvm-service` / `eia.quality`)
- ktlint・detekt・Kover・kotest 設定、`integrationTest` ソースセット規約
- Konsist によるアーキテクチャテスト雛形(`tools/architecture-test`)
- `.editorconfig`、`.gitignore`、`Makefile`、GitHub Actions CI(build + test + cache)
- **DoD**: 空モジュール群で `./gradlew build` が CI 上で成功。Konsist が依存方向違反を検出するテストを持つ。

## P01 Shared Kernel & Canonical Model (KMP)
- `shared/kernel`: `Result`/`DomainError` 階層、`CorrelationId`、`IdempotencyKey`、`RetryPolicy`(Exponential Backoff + Jitter の純粋計算)、`Clock`、`Money`/`Currency`
- `shared/canonical-model`: Customer / Product / Order / OrderLine / Invoice / Shipment(kotlinx.serialization、UTC、通貨明示)
- targets: jvm, js(IR), linuxX64, macosArm64
- **DoD**: commonTest で全ターゲットのテストが成功。フレームワーク依存ゼロ。

## P02 Contracts & Governance CI
- `contracts/openapi/order-api.v1.yaml`(OpenAPI 3.1)、`contracts/asyncapi/order-events.v1.yaml`(AsyncAPI 3.0)、`contracts/avro/*.avsc`
- `contracts/catalog/*.yaml`: 連携カタログ(schema は INTEGRATION_STANDARDS.md 参照)
- `tools/contract-check`: 命名規約 lint、Avro BACKWARD 互換検査(main との差分)、OpenAPI 破壊的変更検知、カタログ必須項目検証
- GitHub Actions `contract-check.yml`(contracts/** 変更時)
- **DoD**: 意図的な破壊的変更・命名違反・Owner 欠落のサンプルで CI が失敗することをテストで示す。

## P03 Local Infrastructure
- `infra/local/docker-compose.yml`: Kafka(KRaft)、Kafka Connect + Debezium、Apicurio、PostgreSQL(サービス別 DB)、Keycloak(realm import)、APISIX、Mosquitto、MinIO、SFTP、OTel Collector、Prometheus、Grafana、Tempo、Loki、Toxiproxy
- プロファイル分割(`core` / `cdc` / `iot` / `b2b` / `observability`)でメモリ負荷を調整
- `make up/down/logs/ps`、ヘルスチェック、Grafana データソース provisioning
- **DoD**: `make up` で全コンテナが healthy。README に起動手順とポート一覧。

## P04 Platform Libraries
- `platform/observability`: OTel 初期化、Ktor プラグイン(traceparent / X-Correlation-Id 伝搬)、Kafka ヘッダ伝搬、構造化 JSON ログ(MDC)
- `platform/security`: Ktor JWT 検証(JWKS, iss/aud/exp)、スコープ認可、Client Credentials トークン取得クライアント、Secrets は環境変数経由
- `platform/reliability`: Timeout / Retry / Circuit Breaker / Bulkhead / Fallback(coroutines ベース、kernel の RetryPolicy を利用)
- `platform/messaging-kafka`: 型付き Producer/Consumer、CloudEvents ヘッダ、Avro Serde(Apicurio)、冪等消費(processed_message)、リトライ→DLQ、Replay CLI
- **DoD**: 各部品に単体テスト + Testcontainers 統合テスト。Toxiproxy で Circuit Breaker の遷移を検証。

## P05 API Integration: order-service
- Clean Architecture 4 モジュール構成。`POST /v1/orders`(Idempotency-Key 必須)、`GET /v1/orders/{id}`
- APISIX 経由公開(JWT 検証・Rate Limit・Correlation ID 付与)、Keycloak Client Credentials
- エラーは RFC 9457 Problem Details
- **DoD**: 同一 Idempotency-Key 再送で同一レスポンス、429 に Retry-After、トレースが Tempo で確認できる。

## P06 Outbox & CDC
- `platform/outbox`: 業務更新と同一 Tx で outbox 挿入
- Debezium Outbox Event Router で `sales.order.created.v1` へ発行
- `legacy-sim`(レガシー DB を模擬)→ Debezium CDC → Anti-Corruption 変換 → 整形済みトピック
- Snapshot + Incremental、整合性チェックジョブ(件数/ハッシュ)
- **DoD**: アプリを Kafka 停止中に動かしても、復旧後にイベントが欠損なく発行される(統合テスト)。

## P07 Event Integration + Saga
- inventory / payment / shipping サービス(各 Consumer Group)
- 注文 Saga(Orchestration 方式、補償イベント含む)。状態は saga テーブルで永続化
- DLQ・Replay 運用 Runbook(docs/runbooks/)
- **DoD**: 在庫不足・決済失敗で補償が走る E2E テスト。Poison Message が DLQ に隔離され本流が止まらない。

## P08 Batch & ETL
- `batch-etl` ジョブ: 受注 DB → 分析 DB(Postgres を DWH と見立て)への Incremental Load(Watermark + Checkpoint)、Upsert で再実行安全
- 週次 Full 照合ジョブ、SLA 遅延予測アラート用メトリクス
- **DoD**: ジョブを途中 kill → 再実行で重複・欠損ゼロ。

## P09 File Integration
- 日次売上ファイル出力: Parquet / CSV、gzip、SHA-256 manifest、一時名→リネーム
- MinIO / SFTP 送信、受信側検証(checksum・件数・スキーマ版)、PGP 暗号化オプション
- **DoD**: 改竄・欠損ファイルを受信側が拒否するテスト。

## P10 SaaS / Webhook / iPaaS-like Flow
- `saas-mock`(CRM を模擬、ページング・レート制限あり)
- Webhook Receiver: HMAC 署名検証 → 冪等化 → 社内 Event Bus へ変換発行
- `integration-flow` モジュール: 宣言的 Flow(YAML: trigger → map → route → deliver)の最小実装、Canonical 経由マッピング
- **DoD**: 署名不正を拒否、SaaS 429 時に Backoff、Flow 定義だけで新規マッピングが追加できる。

## P11 IoT (MQTT) & KMP SDK
- `shared/integration-sdk`: Ktor Client ベースの KMP SDK(認証・リトライ・Correlation ID)。Kotlin/Native でデバイスシミュレータ CLI
- Mosquitto → MQTT-Kafka Bridge(QoS1、デバイス ID をキー)→ `iot.telemetry.reported.v1`
- **DoD**: native バイナリのシミュレータから送信したテレメトリが Kafka に到達し、トレース可能。

## P12 B2B / EDI Gateway
- `b2b-gateway`: Trading Partner 台帳、SFTP 受信、EDIFACT ORDERS(サブセット)パーサ、3 段 Validation、Canonical 変換、CONTRL 相当の受領応答、原本保管(MinIO, WORM 相当設定)
- **DoD**: 構文・スキーマ・業務エラーそれぞれで正しいエラー応答と運用通知。

## P13 gRPC & GraphQL BFF
- inventory の在庫照会を gRPC(内部)で公開、proto は contracts/proto
- `bff-graphql`: 注文 + 在庫 + 出荷の集約クエリ(クエリ深さ/複雑度制限)
- **DoD**: 契約から生成したスタブで通信。GraphQL の過大クエリを拒否。

## P14 Resilience, E2E & SLO
- Toxiproxy による障害注入シナリオ(遅延・切断・Broker 停止)
- E2E シナリオスクリプト、Grafana ダッシュボード(RED、Consumer Lag、DLQ、Batch SLA)、SLO バーンレートアラート
- Tier 別 NFR(Framework 18 章)の計測レポート
- **DoD**: 全シナリオが自動実行でき、ダッシュボードで可視化される。
