# Implementation Roadmap

各フェーズ = GitHub Milestone。フェーズ内の各項目 = Issue。1 フェーズ 1 PR を原則とする。
サンプル業務ドメイン: **EC 受注 → 在庫引当 → 決済 → 出荷**(Framework 1.6 のシナリオ)。
構成の改訂履歴: ADR-0009(P00 キックオフレビュー)。

| Phase | 名称 | 主な Framework 章 | 状態 |
|---|---|---|---|
| P00 | Repository Bootstrap | 16, 19 | 完了(2026-09-25) |
| P01 | Shared Kernel & Canonical Model (KMP) | 15 | 完了(2026-09-25) |
| P02 | Contracts & Governance CI | 5, 6, 15, 16 | 完了(2026-09-26) |
| P03 | Local Infrastructure | 2, 17 | 完了(2026-09-26) |
| P04a | Platform: Observability, Security & Audit | 12, 14 | 完了(2026-09-29) |
| P04b | Platform: Resilience | 13 | 完了(2026-09-30) |
| P05 | API Integration: order-service | 5, 12 | 完了(2026-10-02) |
| P06 | Outbox & CDC | 8 | 完了(2026-10-08) |
| P07 | Event Integration + Saga | 4, 6, 13 | 完了(2026-10-10) |
| P08 | Batch & ELT/ETL | 7 | 完了(2026-10-15) |
| P09 | File Integration | 9 | 完了(2026-10-20) |
| P10 | SaaS / Webhook / iPaaS-like Flow | 10 | 完了(2026-10-23) |
| P11 | IoT (MQTT) & KMP SDK | 6, 17.3 | 完了(2026-10-30) |
| P12 | B2B / EDI Gateway | 11 | 完了(2026-11-02) |
| P13 | gRPC & GraphQL BFF | 3, 5, 12 | 完了(2026-11-03) |
| P14 | Resilience, E2E & SLO | 13, 14, 18 | 完了(2026-11-07) |

状態の日付は、GitHub の Milestone を閉じた日(UTC)。P00〜P14 をすべて実装した。残りの Issue は末尾の「P14 の後に残る Issue」。

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
- `tools/contract-check`: 命名規約 lint(Topic / `cmd-` コマンドトピック / API パス / ファイル)、Avro FULL 互換検査(main との差分。オフラインで実行。ADR-0014)、OpenAPI 破壊的変更検知、カタログ必須項目検証、コマンドトピックの購読者が 1 つであることの検査(ADR-0006)
- Canonical Model(Kotlin)と Avro スキーマの項目一致を検査する JVM テスト(ADR-0004)
- GitHub Actions `contract-check.yml`(contracts/** 変更時)
- **DoD**: 意図的な破壊的変更・命名違反・Owner 欠落・Canonical と Avro の不一致のサンプルで CI が失敗することをテストで示す。

## P03 Local Infrastructure
- `infra/local/docker-compose.yml`: Kafka(KRaft)、Kafka Connect + Debezium、Apicurio、PostgreSQL(サービス別 DB)、Keycloak(realm import)、APISIX、Mosquitto、SeaweedFS(S3 互換。ADR-0015 で MinIO から変更)、SFTP、OTel Collector、Prometheus、Grafana、Tempo、Loki、Toxiproxy
- profiles: `core`(kafka, registry, postgres, keycloak, apisix, otel 一式)/ `cdc` / `iot` / `file` / `b2b` / `chaos`。`secure`(Kafka の SSL・ACL)は P03 から外し Issue #26 で扱う(ADR-0016 §8)。メモリ 16GB の開発マシンで `core` が動くようリソース制限を設定する
- Toxiproxy 経由で Kafka に接続するための専用リスナー(advertised listeners)を用意する
- Kafka・Debezium・Apicurio などのイメージは最新の安定版を確認して固定する
- `make up/down/logs/ps`、ヘルスチェック、Grafana データソース provisioning、ポート一覧(`infra/local/README.md`)
- **DoD**: profile ごとに `make up PROFILE=<name>` で全コンテナが healthy になる。README に起動手順とポート一覧がある。
- **DoD**: Apicurio の互換性ルールを FULL_TRANSITIVE に設定する(ADR-0014)。

## P04a Platform: Observability, Security & Audit
- `platform/observability`: OTel 初期化、Ktor プラグイン(traceparent / X-Correlation-Id 伝搬。生成と解析は `shared/resilience` を使用)、構造化 JSON ログ(MDC)、マスキングユーティリティ
- `platform/security`: Ktor JWT 検証(JWKS, iss/aud/exp)、スコープ認可、Client Credentials トークン取得クライアント、`SecretProvider` Port と環境変数による実装(ADR-0008)
- `platform/audit`: 追記専用テーブル + ハッシュチェーン、S3 互換ストレージ(SeaweedFS。Object Lock)への日次アンカー保存(ADR-0008・ADR-0015)
- **DoD**: 各部品に単体テストと Testcontainers 統合テストがある。改竄したレコードをハッシュチェーンの検証が検出する。不正な iss / aud / exp の JWT を拒否する。

## P04b Platform: Resilience
- `shared/resilience`(KMP): Timeout / Retry / Circuit Breaker / Bulkhead / Fallback(coroutines ベース。kernel の RetryPolicy を利用し、時間の源(単調な TimeSource)と Random をインジェクション)(ADR-0004・ADR-0021)
- `platform/reliability`(JVM): Ktor Client への結線、メトリクス(OTel)出力
- **DoD**: `shared/resilience` の commonTest が jvm / js / linuxX64 で成功する。Toxiproxy で Circuit Breaker の Closed → Open → Half-Open → Closed の遷移を統合テストで検証する。

## P05 API Integration: order-service
- Clean Architecture 4 モジュール構成。`POST /v1/orders`(Idempotency-Key 必須)、`GET /v1/orders/{id}`。Gateway 公開パスは `/sales/v1/orders`(ADR-0005)
- APISIX 経由公開(JWT 検証・Rate Limit・Correlation ID 付与・prefix 書き換え)、Keycloak Client Credentials
- 社外から受け取った `traceparent` の扱い(Gateway で捨てて付け直すか、信じるか)を決める。サービスのサンプラは ParentBased のため、外部の sampled フラグがそのまま効く(ADR-0018)
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
- S3 互換ストレージ(SeaweedFS)/ SFTP 送信、受信側の検証(checksum・件数・スキーマ版)、PGP 暗号化オプション
- Parquet の書き出し手段(ライブラリ)を比較して ADR に残す
- **DoD**: 改竄・欠損ファイルを受信側が拒否するテストがある。manifest の traceparent でトレースがつながる。

## P10 SaaS / Webhook / iPaaS-like Flow
- `saas-mock`(CRM を模擬。ページング・レート制限あり)
- Webhook Receiver: HMAC 署名検証 → 冪等化 → 社内 Event Bus へ変換して発行
- `integration-flow` モジュール: 宣言的 Flow(YAML: trigger → map → route → deliver)の最小実装、Canonical 経由のマッピング。Flow 定義の JSON Schema を用意し、定義ミスを起動時に検出する
- **DoD**: 署名不正を拒否する。SaaS の 429 で Backoff する。Flow 定義だけで新しいマッピングを追加できる。

## P11 IoT (MQTT) & KMP SDK
- `shared/integration-sdk`: Ktor Client ベースの KMP SDK(認証・`shared/resilience` によるリトライ・Correlation ID / traceparent)。エンジンはプラットフォーム別(CIO / JS / Darwin / Curl)。HTTP の部分(`http`)と MQTT の部分(`mqtt`)のモジュールに分ける(シミュレータは `mqtt` だけに依存。ADR-0039 §1)
- `tools/device-simulator`: Kotlin/Native のデバイスシミュレータ CLI。MQTT v5 クライアント(ライブラリ採用か ktor-network での最小実装か)を ADR で決める
- Mosquitto → MQTT-Kafka Bridge(QoS1、デバイス ID をキー)→ `iot.telemetry.reported.v1`
- **DoD**: native バイナリのシミュレータから送信したテレメトリが Kafka に到達し、MQTT v5 のユーザープロパティで運んだ traceparent でトレースできる。

## P12 B2B / EDI Gateway
- `b2b-gateway`: Trading Partner 台帳、SFTP 受信(AS2 は範囲外: ADR-0008)、EDIFACT ORDERS(サブセット。仕様は `contracts/files/edi/`)パーサ、3 段 Validation、Canonical 変換、CONTRL 相当の受領応答(構文とスキーマの段階は CONTRL、業務の段階は APERAK と読み替える: ADR-0041 §9)
- 原本保管(S3 互換ストレージ, Object Lock)と `platform/audit` への記録
- **DoD**: 構文・スキーマ・業務のそれぞれのエラーで、正しいエラー応答と運用通知が出る。受信した全ドキュメントが Audit に記録され、ハッシュチェーンの検証が通る。

## P13 gRPC & GraphQL BFF
- inventory の在庫照会と shipping の出荷照会を gRPC(内部)で公開する(BFF が出荷も読むため、shipping を加えた。ADR-0042 §1)。proto は contracts/proto、mTLS 必須(ADR-0008)
- `bff-graphql`: 注文 + 在庫 + 出荷の集約クエリ(クエリ深さ・複雑度の制限)。GraphQL ライブラリは ADR で選ぶ
- **DoD**: 契約から生成したスタブで通信する。mTLS なしの gRPC 接続を拒否する。GraphQL の過大なクエリを拒否する。

## P14 Resilience, E2E & SLO
- Toxiproxy による障害注入シナリオ(遅延・切断・Broker 停止)を `tests/e2e` に集約する
- Grafana ダッシュボード(RED、Consumer Lag、DLQ、Batch SLA、replication slot 遅延)、SLO バーンレートアラート
- Tier 別 NFR(Framework 18 章)の計測レポート(`docs/reports/p14-nfr.md`)。ADR-0008 で範囲外にした項目も明記する
- 障害シナリオごとに「期待挙動 / 観測方法 / 結果」を `docs/reports/p14-resilience.md` に記録する
- **DoD**: 全シナリオが自動実行でき、ダッシュボードで可視化される。全アラートが Runbook に紐づいている。

---
## P14 の後に残る Issue
P14 を閉じた時点(2026-11-07)で開いている Issue と、残す理由・待つ条件。

| Issue | 内容 | 残す理由・待つ条件 |
|---|---|---|
| #19 | detekt 2.0 安定版への移行 | detekt 2.0 の安定版が出て、使っている Kotlin(2.4.20)に対応するのを待つ |
| #76 | Outbox の保持期間の方式(日ごとのパーティションと DROP) | 行を残す必要のあるサービスが出たら実装する(ADR-0007 §2)。#80 の再発行をより忠実にする方式としても記録した(ADR-0046) |
| #82 | CDC: レガシーの受注の明細の表を結合する(状態を持つ ACL) | 明細(注文全体)を必要とする消費者が出てくるのを待つ。方式はその時点で ADR で決める |
| #139・#145・#148・#161 | pin-check: 上流がタグを別のダイジェストに付け直した(DEBIAN・POSTGRES・MOSQUITTO・PROBE) | 固定したダイジェストは取れる(付け直しの通知)。上流が頻繁に付け直すので、イメージの更新(infra/local/README.md の「イメージの更新」)でまとめて直す |
| #205 | CI: macOS のジョブがランナーの空き不足で動かない | 2026-11-04 の後、macOS のジョブは 6 回続けて成功している。再発しなければ閉じる |
| #213 | ローカルの Kafka のデータが volume に残らない | P14 ⑩ の後に直す(`KAFKA_LOG_DIRS` を volume の付け先に合わせる) |
| #215 | Saga の段の期限と Kafka Connect の割り当て直しの待ち時間 | 期限・送り直しの間隔と Connect の待ち時間の関係を比べて、ADR(ADR-0029 の改訂など)で決める。`docs/reports/p14-nfr.md` の既知の問題 3 |

### Issue にしていない持ち越し
必要になったら Issue にする。

- 画面の部分応答の割合を SLI にする(遅延が大きいときの部分応答が、どのアラートにも映らない。ADR-0048 §7.3、`docs/reports/p14-nfr.md` の既知の問題 4)
- order-service の Saga の消費側の流れ(3,000 件を一度に受けた直後に、返事から次のコマンドまで p99 12〜19 秒。既知の問題 1)
- BFF → Gateway(ADR-0043 §9)と b2b-gateway → Gateway で、トレースが分かれる
- 各サービスが自分の利用者で Kafka に接続する(`secure` profile では、コンテナ間のリスナーは平文のまま。ADR-0047 §3)
- ランナーでの計測の起動した後の上限(`max_load`)の既定(今は 6。ランナーで何もしていないときの負荷は 0.43〜1.71。ADR-0048 §7.4)
