# ADR-0037: KMP の連携クライアント SDK(HTTP の部分)と、JVM の platform との共有
- Status: Accepted
- Date: 2026-10-23
- Framework 参照章: 5, 12, 13, 14

## Context
P11(#14)で、KMP の連携クライアント SDK(`shared/integration-sdk`)を作る(ROADMAP P11・ADR-0004)。SDK は社内の API を、JVM・JS・native(linuxX64・macosArm64)から同じ規約(認証・リトライ・traceparent・Correlation ID)で呼べるようにする。e2e(JVM)のトークンの取得も SDK に置き換える(#14 のコメント。期限の前に取り直す処理を 2 か所に持たない)。

SDK に要る処理の多くは、JVM の platform に既にある。
- `platform/reliability` の `HttpCallClassifier`・`HttpCallError`・`RetryAfter`: Ktor Client の結果を Retryable / NonRetryable に分ける(ADR-0021 §3)。
- `platform/security` の `ClientCredentialsTokenProvider`(と応答の解析・エラーの型): 期限の前に取り直す・同時の取得を 1 本にまとめる・取り直しに失敗したら期限内のトークンを使い続ける・呼び出し元の締め切りを引き継ぐ(ADR-0019 §4・ADR-0021 §11・§12)。

SDK に同じものを別に作ると、同じ規約の処理が 2 か所にでき、片方だけが直されていく。

## Decision
### 1. モジュールの分け方
SDK を 2 つのモジュールに分ける(ADR-0039 §1。MODULE_DESIGN)。互いに依存しない。
| モジュール | 中身 | ターゲット |
|---|---|---|
| `shared/integration-sdk/http`(`io.eia.shared.sdk.http`) | Ktor Client の呼び出しの分類・`Retry-After`・Client Credentials のトークン・エンジンの選択・社内 API のクライアント | jvm, js(IR), linuxX64, macosArm64 |
| `shared/integration-sdk/mqtt`(`io.eia.shared.sdk.mqtt`) | MQTT v5 のクライアント(ADR-0038) | jvm, linuxX64, macosArm64(ADR-0038 §2) |

- `tools/device-simulator` は `mqtt` だけに依存する(HTTP の Curl のエンジン(libcurl)を native のバイナリに巻き込まない)。
- リトライ・Circuit Breaker・締め切り・traceparent は `shared/resilience`、待ち時間の計算は kernel の `RetryPolicy`、Correlation ID・Idempotency-Key は kernel を使う。SDK に同じ処理を作らない。
- commonMain が import してよいのは、Kotlin・kotlinx(coroutines・serialization・datetime・io)・Ktor Client とその要求・応答の型(`io.ktor.client`・`io.ktor.http`・`io.ktor.utils.io`)・kernel・resilience・SDK 自身だけ(Konsist の `sharedImportAllowList`。ADR-0004 §2 の例外を `io.ktor.client` から、この 3 つのパッケージに広げた。`io.ktor.server` は SDK でも禁止)。

### 2. JVM の platform と処理を共有する(移す)
JVM の platform にあった処理の中身を `shared/integration-sdk/http` に移し、platform は JVM に固有の部品をつなぐだけにする。**振る舞いは変えない**(移したテストがそのまま通ることで確かめる)。
| 移したもの | 移した先 | platform に残したもの |
|---|---|---|
| `HttpCallClassifier`・`HttpCallError`(`HttpCallUnavailable`・`HttpCallRejected`)・`RetryAfter` | `io.eia.shared.sdk.http` | `platform/reliability` は `ResilienceMetrics`(OTel)だけ。Ktor に依存しなくなった |
| `ClientCredentialsTokenProvider` の中身・`AccessToken`・`TokenError` の型・応答の解析・`ScopeToken` | `io.eia.shared.sdk.http.token` | `platform/security` の `ClientCredentialsConfig`(`URI` と Secret の名前)と `ClientCredentialsTokenProvider`(SecretProvider から Secret を読み、出来事を slf4j のログに残す) |

- **JVM に固有の部分**は、KMP の側では引数で受け取る。
  - Client Secret は `ClientSecretSource`(取得のたびに呼ぶ)。platform は `SecretProvider` から読んで渡す。`ClientSecretUnavailable.cause` は SecretProvider の `SecretError` から `DomainError` に変えた(KMP の側は SecretProvider を知らない。platform では、これまでどおり `SecretNotFound` などが入る)。
  - ログは `TokenProviderListener` で知らせる。platform は、これまでと同じロガー(`io.eia.platform.security.token.ClientCredentialsTokenProvider`)と文言で出す。接続できない例外の型は、KMP の共通のコードで取れる単純な名前にした(DEBUG のログだけの違い)。
  - 名前解決の失敗(JVM の CIO が投げる `UnresolvedAddressException`。`IOException` ではない)は `expect` の型にし、JVM 以外では投げられない型にした。
  - `client_secret_basic` のエンコード(JVM の `URLEncoder` と同じ結果)と Base64 は、KMP の共通のコードで行う。
- **依存の向き**: `platform:security` は `shared:integration-sdk:http` に依存する(platform → shared は許可された向き。MODULE_DESIGN §2)。`platform:security → platform:reliability` の依存はなくなったので、Konsist の許可の一覧(`PlatformDependencyRules`)から外した。`HttpCallClassifier` を使うサービスの adapters(batch-etl・integration-flow)は、`shared:integration-sdk:http` に直接依存する。
- **テスト**: 移した処理のテスト(`ClientCredentialsTokenProviderSpec`・`HttpCallClassifierSpec`)は SDK の jvmTest に移した。共通のコードがどのターゲットでも同じに動くことは、commonTest(`TokenProviderCommonSpec`・`RetryAfterSpec`・`FormEncodingSpec`)で jvm・js・native のすべてで確かめる。platform には、つないだ部分(SecretProvider・ログ・値の漏れ)のテストだけを残した。

### 3. エンジンの選択(expect/actual)
- 既定のエンジンを `expect` で宣言し、ターゲットごとに actual を置く。
  | ターゲット | エンジン |
  |---|---|
  | jvm | CIO |
  | js | Js |
  | macosArm64 | Darwin |
  | linuxX64 | Curl |
- エンジンは引数で差し替えられる(テストは MockEngine)。
- Ktor 3.6 の Curl のエンジンは、linuxX64 では libcurl を静的にリンクする(リンクした実行ファイルが動的に読むのは libc・libz などのシステムのライブラリだけで、`libcurl.so` を読まないことを確かめた)。CI と実行環境に libcurl を入れる必要はない。

### 4. 社内 API のクライアント
- 依存先ごとに 1 つの `Resilience` を作って使い回し、1 回の呼び出しを `Resilience.execute` で包む(ADR-0021)。結果は §2 の `HttpCallClassifier` で分け、429 / 503 の `Retry-After` を待ち時間に使う(kernel の RetryPolicy が優先する)。
- 要求ごとに `traceparent`(呼び出し元のものがあればその子。なければ新しく始める)と `X-Correlation-Id`(呼び出し元のものを引き継ぐ。なければ採番)を付ける(Framework 14)。
- POST は `Idempotency-Key` を必須の引数にする(Framework 5.4)。リトライでも同じキーを送る。
- 認証は §2 の Client Credentials のトークン(期限の前に取り直す)。401 なら、そのトークンを捨てて 1 回だけ取り直して送り直す。
- 結果は `io.eia.shared.kernel.Result` で返す(`kotlin.Result` は使わない。ADR-0004)。

### 5. e2e のトークンの取得
`tests/e2e` の独自のトークンのヘルパー(`E2eEnvironment.token()`)を、SDK の Client Credentials のトークンに置き換える(#14 のコメント)。

## Alternatives Considered
- **SDK に KMP の版を別に作り、platform のものは残す**: 同じ規約(期限の前の取り直し・分類・Retry-After)の処理が 2 か所にでき、直すときに片方を忘れる。不採用。
- **SDK が platform を使う**: platform は JVM だけなので、KMP の SDK からは使えない。不採用。
- **分類と Retry-After を `shared/resilience` に置く**: Ktor Client の型(応答・例外)に依存する。resilience はフレームワークに依存しない許可の一覧(kotlin・coroutines・kernel だけ)を保つ。不採用。
- **platform に typealias を残して import を変えない**: 型の置き場所が 2 つあるように見え、読む人を迷わせる。import を書き換えた(使っているのは platform/security の中・batch-etl・integration-flow だけ)。

## Consequences(トレードオフ)
- JVM のサービスのトークンの取得・HTTP の分類は、KMP の共通のコードで動く。直すときは SDK を直す(platform は JVM の部品をつなぐだけ)。
- `ClientSecretUnavailable.cause` の型が `DomainError` になった(公開の API の変更。使っていたのは platform/security のテストだけ)。
- SDK の JS のターゲットのため、Kotlin/JS のテストが Ktor Client の JS のエンジン(`ws`)と kotlinx-datetime(`@js-joda/core`)を npm から入れる。`ws` は脆弱性の修正版に固定した(`npm-ws`。docs/runbooks/dependabot-kotlin-js.md)。
- linuxX64 の Curl のエンジンは libcurl を静的に持つので、SDK の HTTP の部分を使う native のバイナリは大きくなる。シミュレータは HTTP の部分に依存しない(§1)。

## 改訂履歴
- 2026-10-23: P11 ②a(#14)で作成した。§2 の移動を行った。§3〜§5 は ②b で実装する。
- 2026-10-24: P11 ②b で §3〜§5 を実装した。§3 の既定のエンジンは `defaultEngine()`(expect/actual)。§4 のクライアントは `IntegrationClient`(`get`・`post`。呼び出しの文脈は `CallContext`)。traceparent と Correlation ID は 1 回の呼び出しで 1 つにし、リトライでも同じ値を送る。§5 の e2e のトークンの取得は `E2eEnvironment.token()` の中で SDK の `ClientCredentialsTokenProvider` を使うようにした(クライアントの認証は client_secret_post から client_secret_basic に変わった。realm の `eiaf-e2e` は両方を受け付ける)。§3 の libcurl は、静的にリンクされていて実行環境に要らないことを確かめたので、Consequences を直した。
