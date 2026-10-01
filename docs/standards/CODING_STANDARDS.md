# Coding Standards (Kotlin / KMP)

## 一般
- Kotlin 公式コーディング規約 + ktlint(official)+ detekt(プロジェクト設定)。警告ゼロで CI 通過。
- 明示 API モード(`explicitApi()`)を shared/* と platform/* で有効化。
- `!!` 禁止。`lateinit` は DI/テスト以外禁止。
- 値オブジェクトは `@JvmInline value class` で表現(ID・金額・キー)。
- 時刻は `kotlin.time.Instant`(UTC)。`kotlin.time.Clock` をインジェクションしテスト可能にする(テストは kernel の `FixedClock`。ADR-0011)。
- 永続化やイベントに載せる時刻は、受け付けた時点で `Instant.truncatedToMicros()`(shared/kernel)でマイクロ秒に切り捨てる(PostgreSQL と Avro の timestamp-micros の精度に合わせる。ADR-0012 §3)。テストではナノ秒の端数を持つ時計を使い、精度の食い違いを手元でも再現できるようにする。
- 金額は kernel の `Money`(最小通貨単位の Long + `Currency`)。浮動小数点で金額・率を扱わない。率は `Rate`(分数 / basis points)で表し、率を掛ける演算では `RoundingMode` を必ず明示する(ADR-0011)。
- 非同期は coroutines。`GlobalScope` 禁止。ブロッキング I/O は `Dispatchers.IO`。

## エラー処理
- domain/application: `Result<T, DomainError>`(kernel 提供)で返す。`DomainError` は sealed interface で、直下は `Retryable` / `NonRetryable` の 2 つ。サービスのエラーはどちらか一方だけを実装する(両方の実装は Konsist で禁止。ADR-0011)。
- adapters: 外部例外を捕捉し、Retryable / NonRetryable に分類して上位へ。
- REST 応答は RFC 9457 Problem Details(`type`, `title`, `status`, `detail`, `correlationId`)。`platform/api` の `installProblemDetails` を入れ、エラーは `call.respondError(error)` で返す。`detail` に例外や `DomainError` のメッセージを入れない(INTEGRATION_STANDARDS §6・ADR-0022 §2)。

## Canonical Model
- 受信点(REST / Kafka / File / Webhook などのデシリアライズ)で Canonical Model を読むときは `CanonicalCodec.decode` を使い、デシリアライズと `validate()` を必ず一緒に行う。`Json.decodeFromString` を直接使わない(`Money` は `@Contextual` のため Codec なしでは扱えない)。送信時も `CanonicalCodec.encode` で検証を通す(ADR-0011 §5)。
- 検証エラーのメッセージに金額やペイロードの値を含めない。個人情報を含むエンティティは `toString` で伏せる。

## ロギング
- SLF4J + JSON エンコーダ。必須キー: `timestamp`, `level`, `service`, `trace_id`, `span_id`, `correlation_id`, `integration_id`, `message`。
- ペイロード全文・個人情報・トークンは記録しない(マスキングユーティリティを使う)。
- 設定は `platform/observability` の共通設定を読み込む(ADR-0018 §3)。標準出力(JSON)と OTLP の両方に出る。
  ```xml
  <configuration>
    <include resource="io/eia/platform/observability/logback-base.xml"/>
  </configuration>
  ```
- 環境変数: `OTEL_SERVICE_NAME`(`service`)、`EIA_LOG_FORMAT`(`json` / `console`。既定 `json`。ローカルで読みやすくするときだけ `console`)、`EIA_LOG_LEVEL`(root のレベル。既定 `INFO`)。
- メッセージ・例外・MDC の値は `Masking` が必ず通る。ただしマスキングは最後の防御なので、外部から受け取った値(本文・ヘッダ・ID 以外の項目)はメッセージに含めず、参照キー(注文 ID など)だけを書く。
- MDC(`trace_id` / `span_id` / `correlation_id` / `integration_id`)は直接書かない。`ObservabilityContext` をコルーチンのコンテキストに入れる(`withContext(...)`)と、スレッドが変わっても保たれる。
- 子の span は `runtime.withSpan("name") { ... }` で作る。OTel の `span.makeCurrent()` を中断(suspend)をまたいで使わない(Scope がスレッドに結び付き、親がずれる。ADR-0018 §2)。

## セキュリティ
- API の認証は `platform/security` の `eiaJwt` で、認可は `requireScopes("sales.order:write") { ... }` で行う(`authenticate { }` の中に置く)。JWT の検証を各サービスで書かない(Nimbus は `platform/security` だけ。Konsist。ADR-0019)。
- 秘密情報は `SecretProvider` から取得し、`Secret.reveal()` は値を渡す直前にだけ呼ぶ。値を変数やフィールドに残さない。ローカルは環境変数か `NAME_FILE`(Docker secrets)で渡す(ADR-0008・ADR-0019 §6)。
- 下流の API のトークンは `ClientCredentialsTokenProvider` で取得する(キャッシュと同時の取得のまとめを含む)。下流が 401 を返したら `invalidate(token)` を呼ぶ。
- トークン・Secret・鍵をログ・例外のメッセージ・`toString` に入れない。秘密情報を持つ型は data class にせず、`toString` で値を伏せる。

## テスト
- テスト名は日本語可(`"同一Idempotency-Keyの再送は同一結果を返す"`)。
- Given/When/Then 構造。Port はフェイク実装を優先し、MockK は adapters 層に限定。
- `Result.Err(...)` や、`data object` を入れ子に含む値を比べるときは `shouldBeEqual`(`equals` で比べる)を使う。kotest 6.2.5 の `shouldBe` は data class をフィールドごとに比べ、期待する値の側の `data object`(プロパティがない)を差がないものとして扱う。そのため `Err(InvalidToken(...)) shouldBe Err(KeysUnavailable)` が通ってしまう(P04a ③ で確認)。
- カバレッジ目標: domain/application 90% 以上、全体 75% 以上(Kover で検証)。統合テストを持つモジュール(adapters など)は、統合テストで実行された本番コードも数え、CI の `integration` ジョブで検証する(MODULE_DESIGN §5.1)。SQL の呼び出しをモックで模しただけの単体テストは書かず、実際のミドルウェアでの統合テストで確かめる。

## Git
- Conventional Commits。scope はモジュール名(`feat(order): ...`, `chore(build): ...`)。
- PR は 400 行差分目安。超える場合は分割。
