# Coding Standards (Kotlin / KMP)

## 一般
- Kotlin 公式コーディング規約 + ktlint(official)+ detekt(プロジェクト設定)。警告ゼロで CI 通過。
- 明示 API モード(`explicitApi()`)を shared/* と platform/* で有効化。
- `!!` 禁止。`lateinit` は DI/テスト以外禁止。
- 値オブジェクトは `@JvmInline value class` で表現(ID・金額・キー)。
- 時刻は `kotlin.time.Instant`(UTC)。`kotlin.time.Clock` をインジェクションしテスト可能にする(テストは kernel の `FixedClock`。ADR-0011)。
- 金額は kernel の `Money`(最小通貨単位の Long + `Currency`)。浮動小数点で金額・率を扱わない。率は `Rate`(分数 / basis points)で表し、率を掛ける演算では `RoundingMode` を必ず明示する(ADR-0011)。
- 非同期は coroutines。`GlobalScope` 禁止。ブロッキング I/O は `Dispatchers.IO`。

## エラー処理
- domain/application: `Result<T, DomainError>`(kernel 提供)で返す。`DomainError` は sealed interface で、直下は `Retryable` / `NonRetryable` の 2 つ。サービスのエラーはどちらか一方だけを実装する(両方の実装は Konsist で禁止。ADR-0011)。
- adapters: 外部例外を捕捉し、Retryable / NonRetryable に分類して上位へ。
- REST 応答は RFC 9457 Problem Details(`type`, `title`, `status`, `detail`, `correlationId`)。

## Canonical Model
- 受信点(REST / Kafka / File / Webhook などのデシリアライズ)で Canonical Model を読むときは `CanonicalCodec.decode` を使い、デシリアライズと `validate()` を必ず一緒に行う。`Json.decodeFromString` を直接使わない(`Money` は `@Contextual` のため Codec なしでは扱えない)。送信時も `CanonicalCodec.encode` で検証を通す(ADR-0011 §5)。
- 検証エラーのメッセージに金額やペイロードの値を含めない。個人情報を含むエンティティは `toString` で伏せる。

## ロギング
- SLF4J + JSON エンコーダ。必須キー: `timestamp`, `level`, `service`, `trace_id`, `span_id`, `correlation_id`, `integration_id`, `message`。
- ペイロード全文・個人情報・トークンは記録しない(マスキングユーティリティを使う)。

## テスト
- テスト名は日本語可(`"同一Idempotency-Keyの再送は同一結果を返す"`)。
- Given/When/Then 構造。Port はフェイク実装を優先し、MockK は adapters 層に限定。
- カバレッジ目標: domain/application 90% 以上、全体 75% 以上(Kover で検証)。

## Git
- Conventional Commits。scope はモジュール名(`feat(order): ...`, `chore(build): ...`)。
- PR は 400 行差分目安。超える場合は分割。
