# ADR-0022: API の共通部品(`platform/api`): Problem Details と Idempotency-Key
- Status: Accepted
- Date: 2026-09-30
- Framework 参照章: 5.2, 5.4, 12, 14
- 関連: ADR-0018 §2(Correlation ID)、ADR-0019 §5(401 / 403 / 503 の応答)、ADR-0005(API パス規約)
- 番号: P05 のサブ PR ごとに予約した番号(0022 platform/api / 0023 gateway)。②a(Problem Details)で作り、②b(冪等)で §3 を追記した

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

### 3. Idempotency-Key(P05 ②b)
**部品**: `io.eia.platform.api.idempotency`。
- `IdempotencyStore`(Port): 記録の保存先。PostgreSQL の実装は各サービスの adapters が持つ(order は P05 ④a)。単体テストはメモリ上のフェイクで書く。
- `IdempotencyHandler`: 判定と処理の中核。HTTP の枠組みに依存しない。
- `respondIdempotently`: Ktor での結線(ヘッダの取り出し・本文の読み込み・指紋・応答)。

**記録**: (クライアント, キー) ごとに 1 件。クライアントは認証したクライアント(JWT の `azp` など)で、別のクライアントが同じキーを使っても影響しない。状態は「処理中」(リースの期限と所有者のトークン)か「完了」(保存した応答と保持期限)。

**判定**:
| 既存の記録 | 指紋が同じ | 指紋が違う |
|---|---|---|
| ない・保持期限切れ | 処理する | — |
| 完了 | 保存した応答を返す(`Idempotent-Replayed: true`) | 422 `idempotency-key-reused` |
| 処理中(リースの期限内) | 409 `idempotency-request-in-progress`(`Retry-After` はリースの残り時間) | 422 |
| 処理中(リースの期限切れ) | 引き継いで処理する | 422(引き継がない) |

- キーがない・複数ある・形式が不正なら、処理せずに 400 `idempotency-key-missing`。同じ名前のヘッダは `,` で 1 行に連結されうる(RFC 9110 §5.3)ため、`,` を含む値も複数とみなす(実装中にテストで確かめた。Ktor の Client も連結して送る)。

**指紋**: SHA-256(メソッド、パス(クエリを含む)、本文を正規化したもの)。
- JSON の本文(`application/json` と `+json`)は、オブジェクトのキーを並べ替え、空白を除いた形にする。キーの順序や空白だけが違う再送(クライアントの JSON ライブラリの違いなど)を、同じ要求とみなすため。
- 数値は書かれた文字列のまま扱う(`1` と `1.0` は別の要求)。値の同一性の判定を型に踏み込ませないため。
- JSON として読めない本文と JSON でない本文は、バイト列のまま使う。

**保存する応答**: 状態コード、許可したヘッダ(`Content-Type`・`Content-Language`・`Location`・`ETag`・`Last-Modified`・`Cache-Control`)、本文。
- `X-Correlation-Id`・`traceparent`・`Set-Cookie`・`Date` などは保存しない。再送への応答には、その要求自身の `X-Correlation-Id` が付く(本文の Problem の `correlationId` は最初の要求の値のまま。問い合わせでは最初の要求を指す)。
- 保持期間は完了から 24 時間(INTEGRATION_STANDARDS §2)。期限切れのキーは新しい要求として扱う。期限切れの記録は `IdempotencyStore.purgeExpired` を定期的に呼んで消す(サービスのジョブで結線する)。
- `purgeExpired` は、保持期限を過ぎた完了の記録に加えて、**リースの期限に猶予(リースの長さ)を足した時刻を過ぎた処理中の記録**も消す。処理中のままプロセスが落ち、再送も来なかった記録が残り続けないようにするため。猶予を足すのは、リースが切れた直後に同じキーの再送が引き継ごうとしている記録を消さないため。
- 本文の上限は既定 1 MiB。超える応答は保存できないので、業務の更新ごと取り消して 500 にする(保存できない応答で業務を確定させない)。

**保存しない応答: 5xx・429・408**。
- どれも一時的な失敗で、同じキーで再試行すれば結果が変わりうる。保存すると、依存先が回復した後も、そのキーでは失敗の応答が 24 時間返り続ける。
- 保存しないときは、**業務のトランザクションを取り消してから**応答し、処理中の記録を消す。「保存しない = 副作用なし」にして、クライアントが同じキーで再試行しても二重の処理にならないようにする。
- そのほかの 4xx(400・404・409・422 など)は保存する。同じ要求への答えは変わらないため。

**「保存しない = 副作用なし」が成り立つ前提**:
- 成り立つのは、**処理の副作用がすべて、同じトランザクション(業務の更新と `IdempotencyStore.complete` を含むもの)の中にある場合に限る**。
- トランザクションの外で行う処理は、取り消しで元に戻らない。外部の呼び出し(下流の API・決済・メールの送信など)と、Outbox を通さないイベントの発行(Kafka への直接の送信)がこれにあたる。応答が 5xx・429・408 になって保存しなかった場合や、例外で取り消した場合に、クライアントが同じキーで再試行すると、**これらは二重に実行されうる**。
- そのため、**イベントの発行は Outbox(P06、ADR-0007)を通す**。Outbox の行は業務の更新と同じトランザクションで書かれるので、取り消されればイベントも発行されない(CLAUDE.md §5「DB→Event は二重書込み禁止」と同じ理由)。
- 外部の呼び出しがどうしても必要な処理は、呼び出し先にも冪等キーを渡す(下流も同じ要求を 1 回として扱う)か、Outbox 経由の非同期の処理(Saga。P07)に分ける。

**リース(処理中の期限)**: 既定 60 秒。
- 処理中のままプロセスが落ちても、期限が過ぎれば同じ要求(同じ指紋)の再送で引き継いで処理する。期限内の再送は 409。
- 引き継ぐたびに所有者のトークンを替える。`IdempotencyStore.complete` と `release` はトークンが一致するときだけ記録を変える(フェンシング)。期限の切れた前の所有者が遅れて戻っても保存できず、`IdempotencyHandler` はそのトランザクションを取り消し、記録を見直して返す(引き継いだ側が完了していれば、その応答)。
- リースは、リクエスト全体の打ち切りの時間(P05 ④b)より長くする。短いと、処理中の要求を別の要求が引き継いで二重に処理しかける(その場合も、後から完了しようとした側はフェンシングで取り消される)。
- **リースと保持期限の設定と判定は、保存先の時刻(PostgreSQL の `clock_timestamp()`)で行い、アプリの時計は使わない。** そのため `IdempotencyStore` は時刻を引数に取らず、期間(リースの長さ・保持期間・猶予)だけを受け取る。処理中の記録には、保存先の時刻で測ったリースの残り時間を返す(`Retry-After` に使う)。
  - 理由: DB を共有する複数のインスタンスの時計はずれうる。アプリの時計で比べると、時計が進んだインスタンスが、ほかのインスタンスの有効なリースを期限切れとみなして横取りし(二重の処理)、期限の前の完了の記録を消しうる。時刻の源を DB の 1 つにすれば、ずれは起きない。
  - `now()` ではなく `clock_timestamp()` を使う。`now()` はトランザクションの開始の時刻で、業務のトランザクション(リースの長さまで続きうる)の中で呼ぶ `complete` では古くなるため。
  - ADR-0021 §6 の単調な時刻は、1 つのプロセスの中の経過時間に使うもので、ここには使えない。
- **指紋が違えば、状態(処理中・完了)やリースの有効・期限切れに関係なく 422**(`idempotency-key-reused`)。リースの切れた処理中の記録でも、指紋の違う要求は引き継がない(引き継ぐと、元の要求の再送が 422 になり、元の要求の処理は失われる)。保持期限を過ぎた完了の記録は、記録がないものとして扱う(新しい要求)。

**原子性の約束**(`IdempotencyStore` の実装が守る):
- `claim` は 1 つの原子的な操作にする(同じ範囲を同時に受け付けて、2 つが処理中にならない)。
- `complete` は呼び出し側のトランザクションに参加する。呼び出し側は `TransactionBoundary` で、業務の更新と `complete` を同じトランザクションにする。

**再送への応答の目印**: `Idempotent-Replayed: true` を付ける。標準のヘッダではないが、Stripe などが同じ名前を使っている。クライアントとログで、再送だったことが分かる。P05 ③ で契約に加える。

## Alternatives Considered
- **order の adapters に置き、2 つ目の REST サービスで切り出す**: 差分は小さいが、P10・P13 までテンプレートとしての再利用が後回しになり、security の 401 / 403 の形も揃わない。不採用。
- **`type` を `about:blank` だけにし、種類は独自の `code` の項目で表す**: RFC 9457 の拡張で表せるが、`type` が種類の識別子であるという RFC の設計から外れ、クライアントの判別の規則が 2 つになる。不採用。
- **`type` を環境ごとの設定値(ドキュメントのホスト)にする**: 環境によってクライアントが受け取る識別子が変わり、判別の規則が壊れる。不採用。
- **解決できない `tag:` の URI**: 識別子としては正しいが、なじみが薄く、将来ドキュメントを置く道がない。不採用。
- **`detail` に例外や `DomainError` のメッセージを入れる**: 調査は楽になるが、SQL・接続先・個人情報・業務の値が漏れうる。調査は `correlationId` とログで行う。不採用。
- **StatusPages の `exception` で例外を扱う**: 上の Decision のとおり、`ServerObservability` の外側で動くため、メトリクスとログが誤る。不採用。
- **`IdempotencyStore` を order の application の Port にする(MODULE_DESIGN の当初の案)**: 保存するのは HTTP の応答で、application が HTTP を知ることになる。不採用。
- **処理中の記録を持たず、一意制約で後の要求を待たせる(業務と同じトランザクションで記録を挿入する)**: 状態が 1 つで済むが、処理中の同じキーに 409 を返せず、長い処理の間、後の要求が DB の接続とロックを持ったまま待つ。不採用。
- **処理中の記録にリースを持たせない**: プロセスが落ちると、そのキーは保持期限まで 409 のままになる。不採用。
- **5xx も保存する**: 依存先が回復した後も、そのキーでは失敗が 24 時間返る。クライアントは新しいキーで再試行するしかなく、新しいキーでは二重の処理を防げない。不採用。
- **5xx でも業務の更新を確定させる(記録だけ消す)**: 再試行で二重の処理になる。不採用。
- **指紋に本文のバイト列だけを使う(正規化しない)**: 実装は単純だが、クライアントの JSON ライブラリの違い(キーの順序・空白)だけで 422 になる。不採用。
- **指紋でスキーマに沿った正規化(数値の型をそろえるなど)をする**: 契約ごとの知識が要り、共通の部品に置けない。不採用。

## Consequences(トレードオフ)
- `type` の基底 URI は、組織で 1 回だけ決める値になる。この参照実装を採用した組織が別の URI にする場合は、`ProblemType.BASE_URI`・INTEGRATION_STANDARDS §6・契約をまとめて変え、クライアントには破壊的変更として扱う。
- 応答の `detail` からは原因が分からない。問い合わせは `correlationId` を起点にログで追う運用になる。
- 401 / 403 の本文の `type` が `about:blank` から `https://eiaf.example/problems/unauthorized` などに変わった(P05 より前に利用者はいない)。
- `installProblemDetails` は Ktor の `Plugins` の段階に interceptor を置く。同じ段階で例外を扱うほかのプラグインを入れる場合は、順序に注意する。
- 冪等の保証は、副作用が同じトランザクションの中にある処理に限られる。イベントの発行は Outbox(P06)が前提になり、Outbox より前のフェーズで外部に送る処理は、再試行で二重に実行されうる。
- 冪等の要求のたびに、記録の読み書き(受け付け・完了)が DB に 2 回増える。
- 再送への応答では、本文の Problem の `correlationId`(最初の要求)と、ヘッダの `X-Correlation-Id`(再送の要求)が異なる。
- 処理がリースより長くかかると、同じキーの再送が処理を引き継ぎ、先の処理は保存の時点で取り消される(フェンシングで二重の確定は防ぐが、処理の時間は無駄になる)。リースは処理の上限より十分長くする。

## 改訂履歴
- 2026-09-30: ②a で §1・§2 を作成した。§3(冪等)は ②b で追記する。
- 2026-09-30: ②b で §3(Idempotency-Key)を追記した。
- 2026-10-01: P05 ④a-2 で、リースと保持期限を保存先の時刻(PostgreSQL の `clock_timestamp()`)で設定・判定することにした。複数のインスタンスの時計のずれで、有効なリースを横取りしたり、期限の前の記録を消したりしないため。これに合わせて `IdempotencyStore` の `claim` / `complete` / `purgeExpired` は時刻を引数に取らず、期間だけを受け取る形に変え、`IdempotencyHandler` はアプリの時計を使わなくなった。`purgeExpired` は、リースの期限に猶予を足した時刻を過ぎた処理中の記録も消す。指紋が違えば、状態やリースの有効・期限切れに関係なく 422 であることを明記した(PostgreSQL の実装の統合テストで確かめた)。
