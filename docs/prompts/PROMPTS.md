# Claude Code Prompts

## 0. 事前準備(人手で 1 回)
```bash
unzip enterprise-integration-architecture-framework.zip && cd enterprise-integration-architecture-framework
gh auth login
./scripts/bootstrap-github.sh enterprise-integration-architecture-framework private   # repo作成・ラベル・P00〜P14 マイルストーン/Issue
claude                                               # リポジトリのルートで Claude Code を起動
```
進め方の基本: **1 フェーズ = 1 セッション = 1 PR**。フェーズ完了ごとに `/clear` してコンテキストをリセットする。

---

## 1. キックオフ(最初のセッションで 1 回)
```
CLAUDE.md と docs/ 配下(architecture/EIA-Framework.md, implementation/*, standards/*, adr/*)を読み、
このリポジトリの目的・技術スタック・規約を 15 行以内で要約してください。
その上で、ROADMAP の P00〜P14 に対して「設計書との不整合」「技術的リスク」「順序の見直し提案」を挙げてください。
コードはまだ書かないでください。提案のうち採用すべきものは ADR 案として示してください。
```

## 2. 各フェーズ共通(カスタムコマンド)
```
/implement-phase P00
```
計画が提示されたら確認して「承認。進めてください」と返す。完了後:
```
/review-integration
```
指摘対応後、「push して PR を作成してください」→ CI 成功を確認してマージ → `/clear`。

---

## 3. フェーズ別の追加指示
`/implement-phase Pxx` の後、計画承認時に以下を追記すると精度が上がる。

### P00 Repository Bootstrap
```
追加指示:
- Gradle / Kotlin / Ktor / Koin / Exposed / kotest / Konsist / detekt / ktlint 等は、Web またはリポジトリで最新安定版を確認して libs.versions.toml に固定。
- build-logic に eia.kmp-library(jvm, js(IR), linuxX64, macosArm64)/ eia.jvm-library / eia.jvm-service / eia.quality の convention plugin を作成。
- tools/architecture-test に Konsist で「domain/application が io.ktor, org.apache.kafka, org.jetbrains.exposed, org.koin を import しない」「依存方向」テストを実装し、違反サンプルで失敗することを確認。
- .github/workflows/ci.yml を完成させ、Gradle キャッシュを有効化。
```

### P01 Shared Kernel & Canonical Model
```
追加指示:
- Result/DomainError は sealed 階層で Retryable/NonRetryable を区別可能に。
- RetryPolicy は副作用なしの純粋関数(attempt → delay)として実装し、jitter は Random をインジェクション。
- Canonical Model は Framework 15 章に従いドメイン単位(sales, catalog, billing, logistics)でパッケージ分割。全体単一巨大モデルにしない。
- commonTest で全ターゲットを実行。
```

### P02 Contracts & Governance CI
```
追加指示:
- 互換性検査は main ブランチの契約と PR の契約を比較する方式にする。
- tools/contract-check は Kotlin CLI とし、失敗理由を「ファイル / ルール / Framework 章」で出力。
- 違反サンプル(命名違反、Avro 必須フィールド追加、OpenAPI の必須パラメータ追加、Owner 欠落)を test fixtures に置き、全て検出されることをテスト。
```

### P03 Local Infrastructure
```
追加指示:
- docker compose profiles: core(kafka, registry, postgres, keycloak, apisix, otel一式), cdc, iot, file, b2b, chaos。
- 開発マシン(メモリ 16GB)で core が動くようリソース制限を設定。
- Keycloak は realm-export.json で client(order-service 等)と scope を自動投入。
- ポート一覧と起動手順を infra/local/README.md に記載。
```

### P04 Platform Libraries
```
追加指示:
- Circuit Breaker / Bulkhead は外部ライブラリ採用 or 自作を比較して ADR に残す(coroutines 親和性・KMP 移植性を評価軸に)。
- Kafka Consumer は「処理 → processed_message 記録 → オフセットコミット」を同一の冪等性保証で実装。
- DLQ レコードに error.class / error.message / retry.count / original.topic / original.offset ヘッダを付与。
- Replay は CLI(DLQ → 元トピック、フィルタ・件数上限・dry-run 付き)。
```

### P05 API Integration
```
追加指示:
- Idempotency: 同一キー・同一ボディ→保存済み応答、同一キー・異なるボディ→422。処理中の同一キー→409。
- APISIX に JWT 検証・limit-count(client_id 単位)・request-id(X-Correlation-Id)を設定。
- OpenAPI 契約と実装の一致をテスト(レスポンスを契約スキーマで検証)。
```

### P06 Outbox & CDC
```
追加指示:
- Debezium Outbox Event Router を使い、aggregate_id をパーティションキーに。
- legacy-sim のテーブルは意図的に「レガシーっぽい」命名(例: T_JUCHU, COL_01)にし、Anti-Corruption 変換で Canonical へ。
- Kafka 停止 → 注文作成 → Kafka 再開 → イベント欠損なし、を Testcontainers で自動テスト。
```

### P07 Event Integration + Saga
```
追加指示:
- Saga は Orchestration 方式(order-service が状態機械を保持)。状態遷移表を docs/ に Mermaid stateDiagram で記載。
- 補償: 在庫引当取消・決済取消。タイムアウト時の補償も実装。
- Poison Message テストと DLQ→Replay の Runbook(docs/runbooks/dlq-replay.md)を作成。
```

### P08〜P14
```
/implement-phase P08    # 以降同様。ROADMAP の DoD が受入基準。
```
- P10: Flow 定義 YAML の JSON Schema を用意し、定義ミスを起動時に検出。
- P11: KMP SDK は commonMain で Ktor Client、プラットフォーム別エンジン(CIO/JS/Darwin/Curl)を expect/actual で選択。
- P12: EDI パーサは対象セグメントを限定したサブセットで良いが、Validation 3 段階は完全実装。
- P14: 障害シナリオごとに「期待挙動 / 観測方法(ダッシュボード)/ 結果」を docs/reports/resilience.md に記録。

---

## 4. 運用系プロンプト
新規連携の追加:
```
/new-integration shipping が出荷完了時に外部 CRM(SaaS)へ通知し、顧客ステータスを更新する
```
設計書の改訂を反映:
```
docs/architecture/EIA-Framework.md の差分(git diff HEAD~1 -- docs/architecture)を読み、
実装・標準・ROADMAP で追従が必要な箇所を一覧化し、Issue 案を作成してください。
```
技術的負債の棚卸し:
```
リポジトリ全体を Framework 20 章のアンチパターン観点で監査し、該当箇所・深刻度・修正方針を表で出してください。修正はしないでください。
```
セッション再開時:
```
CLAUDE.md を読み、git log -10 と gh issue list --state open で現状を把握し、次に着手すべきフェーズと残タスクを提示してください。
```
