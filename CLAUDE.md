# CLAUDE.md — Enterprise Integration Architecture Framework (EIAF)

このリポジトリは「Enterprise Integration Architecture Framework」(docs/architecture/EIA-Framework.md)の
**ローカル参照実装(Reference Implementation)** である。Claude Code は本ファイルを最優先の作業規約として扱うこと。

## 1. 目的
- Framework 設計書の各章(API / Event / CDC / Batch / File / SaaS / B2B / IoT / Security / Reliability / Observability / Governance)を
  **動くコードで実証**し、全社の連携実装テンプレートとして再利用可能にする。
- Cloud Agnostic:特定クラウドのマネージドサービスに依存しない。ローカルは Docker Compose のみで完結させる。

## 1.1 プロジェクト識別子
| 項目 | 値 |
|---|---|
| リポジトリ名 / ルートフォルダ | `enterprise-integration-architecture-framework` |
| Gradle rootProject.name | `enterprise-integration-architecture-framework` |
| 略称(ドキュメント・ログ用) | EIAF |
| basePackage | `io.eia`(ADR-0001。変更時は ADR を更新) |

## 2. 技術スタック(固定)
- 言語: **Kotlin**(JVM 21 toolchain) / **Kotlin Multiplatform (KMP)**
- アーキテクチャ: **Clean Architecture**(domain → application → adapters → bootstrap)
- ビルド: Gradle Kotlin DSL + `gradle/libs.versions.toml` + `build-logic/`(convention plugins)
- サーバ: Ktor(Server/Client)、kotlinx.serialization、kotlinx.coroutines、Koin(DI)
- 永続化: PostgreSQL + Exposed、マイグレーション Flyway
- メッセージング: Kafka(KRaft)、Apicurio Schema Registry、Avro(avro4k)、Debezium(Kafka Connect)、MQTT(Mosquitto)
- セキュリティ: Keycloak(OAuth2/OIDC)、JWT 検証、mTLS(内部)
- Gateway: Apache APISIX(standalone YAML)
- Observability: OpenTelemetry(SDK + Collector)、Prometheus、Grafana、Tempo、Loki
- File: MinIO(S3互換)、SFTP
- テスト: kotest、MockK、Testcontainers、Konsist(アーキテクチャテスト)、Toxiproxy(障害注入)
- 品質: ktlint、detekt、Kover(カバレッジ)
- ライブラリのバージョンは **実装時点の最新安定版を確認して libs.versions.toml に固定**する。推測でバージョンを書かない。

## 3. リポジトリ構成(モノレポ)
```
build-logic/            Gradle convention plugins (kmp-library, jvm-service, quality)
contracts/              契約の単一の真実 (OpenAPI / AsyncAPI / Avro / Proto / catalog YAML)
shared/                 KMP モジュール (commonMain 中心・フレームワーク非依存)
  kernel/               Result, DomainError, CorrelationId, IdempotencyKey, RetryPolicy, Clock
  canonical-model/      Canonical Model (Customer, Order, Product, Invoice ...)
  integration-sdk/      KMP 連携クライアント SDK (jvm / js / native)
platform/               JVM 共通連携部品 (再利用可能な Integration Building Blocks)
  observability/ security/ reliability/ messaging-kafka/ outbox/ file-transfer/ schema-registry/
services/<name>/        サンプル業務サービス。各サービスは下記 4 モジュール構成
  domain/               KMP commonMain。純粋 Kotlin。外部依存禁止
  application/          KMP commonMain。UseCase + Port(in/out)。domain のみ依存
  adapters/             JVM。REST/Kafka/DB/File などの Port 実装
  app/                  JVM。Ktor 起動・Koin 配線・設定
tools/                  ガバナンスツール (catalog validator, naming linter, compat check)
infra/local/            docker-compose とミドルウェア設定
docs/                   設計書・ロードマップ・標準・ADR・プロンプト
```

## 4. Clean Architecture 規約(Konsist で自動検証すること)
- 依存方向は **domain ← application ← adapters ← app** のみ。逆方向・スキップ参照禁止。
- `domain` / `application` は Ktor・Kafka・Exposed・Koin 等のフレームワークを import しない(commonMain で担保)。
- 外部 I/O は必ず application の **Port(interface)** 経由。Adapter は Port を実装する。
- ユースケースは 1 クラス 1 ユースケース(`XxxUseCase` / `operator fun invoke`)。
- 例外は境界で `Result<T, DomainError>` に変換。domain 内で例外を業務制御に使わない。
- DTO(契約モデル)と domain モデルを混同しない。変換は adapters のマッパーで行う。

## 5. 連携実装の必須ルール(Framework 準拠。違反はレビューで却下)
| 区分 | 必須事項 | 参照章 |
|---|---|---|
| Contract First | 実装前に contracts/ に契約を追加し CI 互換性検査を通す | 5, 6, 15, 19 |
| API | Gateway 経由 / `/v{n}/` / POST は `Idempotency-Key` 必須 / 429+Retry-After | 5 |
| Event | Topic `{domain}.{entity}.{event}.v{n}` / Avro + CloudEvents ヘッダ / BACKWARD 互換 / DLQ `{topic}.dlq` | 6 |
| 配信保証 | At-Least-Once + 消費側冪等(processed_message テーブル) | 13 |
| DB→Event | 二重書込み禁止。**Outbox + Debezium** で発行 | 8 |
| 同期呼出し | Timeout + Retry(Backoff+Jitter) + Circuit Breaker + Fallback の 4 点セット | 13 |
| 非同期 | 冪等 + DLQ + Replay の 3 点セット | 13 |
| Batch | 再実行安全(Upsert/パーティション置換) + Checkpoint(Watermark 永続化) | 7 |
| File | manifest(件数・SHA-256・schema版) + 一時名→リネーム完了通知 | 9 |
| 可観測性 | `traceparent` と `X-Correlation-Id` を全チャネル伝搬 / 構造化 JSON ログ / ペイロード全文ログ禁止 | 14 |
| Security | Secrets のハードコード禁止(環境変数/.env は .gitignore) / JWT は iss・aud・exp 検証 | 12 |
| Governance | 新規連携は `contracts/catalog/*.yaml` に登録(Owner・Tier・SLO・機密区分) | 16 |

## 6. 作業プロセス
1. 着手前に `docs/implementation/ROADMAP.md` の該当フェーズと DoD を読む。大きな変更は **Plan を提示してから実装**。
2. ブランチ: `feat/p{NN}-{slug}`、`fix/...`、`docs/...`。コミットは Conventional Commits(`feat(order): ...`)。
3. 1 フェーズ = 1 PR(大きい場合はサブ PR に分割)。PR テンプレートのチェックリストを埋める。
4. アーキテクチャ上の決定は `docs/adr/NNNN-*.md` に ADR として残す(`/adr` コマンド)。
5. 完了前に必ず以下を実行し、全て成功させる:
   - `./gradlew build`(ktlint・detekt・テスト・Konsist 含む)
   - 契約変更時: `./gradlew :tools:contract-check:run`
   - 統合テスト: `./gradlew integrationTest`(Testcontainers)
6. 設計書と実装が乖離する場合は実装で黙って逸脱せず、ADR を書いて理由を残す。

## 7. よく使うコマンド
```bash
make up / make down           # infra/local の docker compose 起動・停止
./gradlew build               # 全ビルド + 品質チェック + 単体テスト
./gradlew integrationTest     # Testcontainers 統合テスト
./gradlew :services:order:app:run
make e2e                      # E2E シナリオ (scripts/e2e)
```

## 8. 禁止事項
- 設計書にない連携方式・ミドルウェアの独断追加(ADR 必須)
- Shared Database(他サービスの DB を直接参照)
- `domain`/`application` へのフレームワーク依存の混入
- テストの無効化・`@Ignore` での CI 通過
- 秘密情報のコミット、`--no-verify`、`git push --force`(main)

## 9. 参照ドキュメント
- 設計書: docs/architecture/EIA-Framework.md
- ロードマップ/DoD: docs/implementation/ROADMAP.md
- モジュール設計: docs/implementation/MODULE_DESIGN.md
- コーディング標準: docs/standards/CODING_STANDARDS.md
- 連携標準(命名/ヘッダ/エラー): docs/standards/INTEGRATION_STANDARDS.md
- ADR: docs/adr/
