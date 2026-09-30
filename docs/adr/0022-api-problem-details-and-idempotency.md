# ADR-0022: API の共通部品(`platform/api`): Problem Details と Idempotency-Key
- Status: Accepted
- Date: 2026-09-30
- Framework 参照章: 5.2, 5.4, 12, 14
- 関連: ADR-0018 §2(Correlation ID)、ADR-0019 §5(401 / 403 / 503 の応答)、ADR-0005(API パス規約)
- 番号: P05 のサブ PR ごとに予約した番号(0022 platform/api / 0023 gateway)。この ADR は ②a(Problem Details)で作り、②b(冪等)で §3 を追記する

## Context
P05 で最初の REST API(order-service の `POST /v1/orders`・`GET /v1/orders/{id}`)を作る。Framework と標準は次を求めている。

- エラーは RFC 9457 の Problem Details で返し、`correlationId` を含める(CODING_STANDARDS「エラー処理」、契約 `order-api.v1.yaml` の `Problem`)。
- 更新系(POST)は `Idempotency-Key` を必須とし、鍵ごとに結果を 24 時間保存して同じ応答を返す(Framework 5.4、INTEGRATION_STANDARDS §2)。

どちらもサービスごとに書くものではなく、全社の API で同じ規則にしたい(P10 の Webhook 受信、P13 の BFF でも使う)。決める必要があるのは、部品の置き場所、Problem Details の `type` の形と、応答に出してよい情報の範囲、冪等の仕様である。

## Decision
### 1. `platform/api` を新しく作る
- パッケージは `io.eia.platform.api`。Ktor の Server に依存する JVM の部品。
- Problem Details(`io.eia.platform.api.problem`)と、Idempotency-Key(②b)を置く。
- platform のモジュール間の依存(MODULE_DESIGN §2、Konsist の `PlatformDependencyRules`)に、次の 2 本を加える。
  - `api → observability`: 応答の `correlationId` を、`ServerObservability` がコルーチンのコンテキストに置いた `ObservabilityContext` から取る。
  - `security → api`: 401 / 403 / 503 を同じ Problem Details で返す(§2。ADR-0019 §5 の持ち越し)。
- MODULE_DESIGN §3 は `IdempotencyStore` を order の application の Port にしていたが、`platform/api` に置く(§3。②b)。保存するのは状態コード・ヘッダ・本文という HTTP の応答であり、application を HTTP から切り離しておくため。

### 2. Problem Details(RFC 9457)
**形**: `type`・`title`・`status`・`detail`(任意)・`correlationId`・`errors`(422 の検証エラーだけ。`field` と `message`)。項目の順は契約の `Problem` と同じ。`instance` は使わない。メディアタイプは `application/problem+json`、`Cache-Control: no-store` を付ける。`Retry-After`(秒。切り上げ)は、再試行の時期が分かるとき(503・429)に付ける。

**`type` の URI**: `https://eiaf.example/problems/{slug}`。
- `type` はクライアントがエラーの種類を判別する識別子であり、環境ごとに変える設定値にしない。変更はクライアントにとって破壊的変更になる。この Framework を組織で採用するときは、最初に基底 URI を 1 回だけ決める。
- `eiaf.example` は RFC 2606 の予約ドメインで、実在のサイトと衝突しない。URI は解決できなくてよい(RFC 9457 §3.1.1)。
- 種類の一覧は INTEGRATION_STANDARDS §6 に載せ、コードでは `ProblemType` に定義する。状態コード以上の意味を持たない応答(405・413・415)は `about:blank` にする(RFC 9457 §4.2.1)。

**応答に出す情報**:
- `title` と `detail` は、種類ごとに決めた固定の文にする。例外のメッセージ・スタックトレース・パーサのメッセージ(本文の断片を含みうる)・`DomainError.message`(業務の値や参照キーを含みうる)は出さない。
- 検証エラー(422)の `errors[].message` には `FieldViolation.reason` を使う。値を含めない約束(CODING_STANDARDS「Canonical Model」)の上で、どの項目をどう直せばよいかをクライアントに伝えるため。
- 例外とスタックトレースは、Correlation ID の付いた ERROR ログにだけ残す(ログはマスキングを通る。ADR-0018 §3)。クライアントは応答の `correlationId` で問い合わせ、運用者はログで追う。
- 401 / 403 / 503(`platform/security`)も同じ形で返す。拒否した理由を返さない方針(ADR-0019 §5)は変えない。

**`DomainError` の既定の写し方**(`Problem.of`): `ValidationError` → 422 `validation-failed`、`NotFoundError` → 404 `not-found`、`ConflictError` → 409 `conflict`、そのほかの Retryable(`ResilienceError` を含む) → 503 `service-unavailable`(`retryAfter` があれば `Retry-After`)、そのほかの NonRetryable → 500 `internal-error`。業務エラーの種類はサービスが `ProblemDetailsConfig.mapper` で決める。写し方の決まっていない業務エラーは、サービスの実装漏れとして 500 にする。

**例外の扱い**(`installProblemDetails`):
| 状況 | 応答 |
|---|---|
| 想定外の例外 | 500 `internal-error` |
| 本文を読めない(`BadRequestException`・`ContentTransformationException`) | 400 `bad-request` |
| `NotFoundException`・どのルートにも当たらない | 404 `not-found` |
| 許可されていないメソッド | 405 `about:blank` |
| `UnsupportedMediaTypeException` / `PayloadTooLargeException` | 415 / 413 `about:blank` |
| キャンセル(クライアントの切断・タイムアウト) | 扱わずに伝える(タイムアウトは Ktor が 504 を返し、`ServerObservability` が記録する。ADR-0018 §5) |
| 応答を送り始めた後の例外 | 状態コードを変えられないので伝える |

- **例外は、Ktor のパイプラインの `Plugins` の段階の interceptor で扱う。StatusPages の `exception` は使わない。** StatusPages の例外の処理(Ktor 3 の `CallFailed`)は `Setup` より前の段階に入り、`ServerObservability`(`Monitoring`)の外側で動く。そこで扱うと、`ServerObservability` には例外がそのまま届き、400 にした応答も 500 として RED メトリクスに数えられ、未処理の例外のログも二重になる(実装中にテストで確かめた)。`Plugins` の段階は `Monitoring` の内側なので、応答の状態コードとログが 1 回で正しく記録され、`correlationId` もその処理のコンテキストから取れる。
- 404 / 405 のうち、状態コードだけの応答(どのルートにも当たらないときなど)は StatusPages の `status` で Problem Details にする。ルートの処理が `respondProblem` / `respondError` で返した応答は上書きしない。

## Alternatives Considered
- **order の adapters に置き、2 つ目の REST サービスで切り出す**: 差分は小さいが、P10・P13 までテンプレートとしての再利用が後回しになり、security の 401 / 403 の形も揃わない。不採用。
- **`type` を `about:blank` だけにし、種類は独自の `code` の項目で表す**: RFC 9457 の拡張で表せるが、`type` が種類の識別子であるという RFC の設計から外れ、クライアントの判別の規則が 2 つになる。不採用。
- **`type` を環境ごとの設定値(ドキュメントのホスト)にする**: 環境によってクライアントが受け取る識別子が変わり、判別の規則が壊れる。不採用。
- **解決できない `tag:` の URI**: 識別子としては正しいが、なじみが薄く、将来ドキュメントを置く道がない。不採用。
- **`detail` に例外や `DomainError` のメッセージを入れる**: 調査は楽になるが、SQL・接続先・個人情報・業務の値が漏れうる。調査は `correlationId` とログで行う。不採用。
- **StatusPages の `exception` で例外を扱う**: 上の Decision のとおり、`ServerObservability` の外側で動くため、メトリクスとログが誤る。不採用。

## Consequences(トレードオフ)
- `type` の基底 URI は、組織で 1 回だけ決める値になる。この参照実装を採用した組織が別の URI にする場合は、`ProblemType.BASE_URI`・INTEGRATION_STANDARDS §6・契約をまとめて変え、クライアントには破壊的変更として扱う。
- 応答の `detail` からは原因が分からない。問い合わせは `correlationId` を起点にログで追う運用になる。
- 401 / 403 の本文の `type` が `about:blank` から `https://eiaf.example/problems/unauthorized` などに変わった(P05 より前に利用者はいない)。
- `installProblemDetails` は Ktor の `Plugins` の段階に interceptor を置く。同じ段階で例外を扱うほかのプラグインを入れる場合は、順序に注意する。

## 改訂履歴
- 2026-09-30: ②a で §1・§2 を作成した。§3(冪等)は ②b で追記する。
