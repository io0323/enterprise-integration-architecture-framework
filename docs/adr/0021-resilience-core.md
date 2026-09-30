# ADR-0021: 回復性の部品(Timeout / Retry / Circuit Breaker / Bulkhead / Fallback)の仕様
- Status: Accepted
- Date: 2026-09-30
- Framework 参照章: 5.5, 13
- 関連: ADR-0004 §5(回復性の中核を KMP に置く)、ADR-0010 Decision 6(shared の許可リスト)、ADR-0011(RetryPolicy と DomainError)、ADR-0019 §4(トークン取得の Retry と Circuit Breaker は P04b で結線する)

## Context
P04b で、同期呼び出しの 4 点セット(Framework 13.3: Timeout + Retry + Circuit Breaker + Fallback)と Bulkhead を、`shared/resilience`(KMP)に coroutines ベースで作る(ADR-0004 §5)。
JVM のサービスだけでなく、P11 の KMP SDK(js / native)でも同じ部品を使う。

既存の資産として、kernel に次の 2 つがある(P01。ADR-0011)。
- `RetryPolicy`: Exponential Backoff + Jitter の待ち時間の計算。maxAttempts は初回を含み、Retry-After を優先し、上限を超える Retry-After では打ち切る。
- `DomainError`: 直下は Retryable / NonRetryable の 2 つだけ。

決める必要があるのは、部品を重ねる順序、Circuit Breaker が失敗として数える範囲、時間の測り方、Retry Storm を防ぐ仕組みの要否、Fallback の渡し方である。

P04b は 2 つの PR に分ける(積み重ねない)。この ADR は両方の決定を持つ。
- ①(この ADR と同時): `shared/resilience` の中核
- ②: `platform/reliability`(OTel のメトリクス・Ktor Client の呼び出しの分類)、`ClientCredentialsTokenProvider` への結線、Toxiproxy の統合テスト

## Decision
### 1. 重ねる順序は固定する
`Resilience`(依存先ごとに 1 インスタンス)が、外側から次の順に重ねる。順序は利用者に選ばせない。

```
Fallback → 締め切り(deadline) → Retry → Circuit Breaker → Bulkhead → 1 回の Timeout → 呼び出し
```

| 順序 | 理由 |
|---|---|
| Retry の内側に Circuit Breaker | 試行ごとに Circuit Breaker を通すため。リトライの 1 回 1 回が失敗率に数えられ、遮断中のリトライは依存先に届かない |
| Circuit Breaker の内側に Bulkhead | 遮断中の呼び出しが Bulkhead の枠を使わないため |
| Bulkhead の内側に 1 回の Timeout | Bulkhead の空きを待つ時間を、依存先の応答時間に数えないため(待つ時間の上限は Bulkhead の `maxWait`) |
| 締め切りは Retry の外側 | リトライを含む呼び出し全体の時間の予算(Framework 13.1 のタイムバジェット)を表すため |
| Fallback は最も外側 | 締め切りを過ぎたときや遮断中も、代替動作を使えるようにするため |

- **締め切り(タイムバジェット)を、試行とリトライの両方に効かせる。**
  - 締め切りは設定(`ResilienceConfig.deadline`)で決め、呼び出しごとに上書きできる(入口から配分された残り時間を渡すため)。
  - 試行の Timeout は `min(attemptTimeout, 残り時間)` にする。残り時間で打ち切った試行は `DeadlineExceeded` を返し、Circuit Breaker とリトライバジェットの失敗に数える。数えないと、`attemptTimeout` より短い締め切りで呼ばれ続けたときに、ハングした依存先に対して Circuit Breaker が開かない。ただし、呼び出し元の締め切り(§12 の `CallDeadline`)で打ち切った試行は数えない(§12)。
  - Bulkhead の空きを待つ時間も、残り時間までに縮める。
  - RetryPolicy が決めた待ち時間が残り時間以上なら、待たずにその時点のエラーを返す(`RetrySuppression.DEADLINE`)。
  - 残り時間が 0 以下で呼ばれたら、呼び出さずに `DeadlineExceeded` を返す。上流で予算を使い切るのは正常に起こりうることなので、例外(プログラムの誤り)にしない。この場合は依存先に送っていないので、Circuit Breaker には数えない。
- **Circuit Breaker が開いたら、リトライせずにすぐ返す。**
  - リトライの待機の前に状態を見て、Open なら待たずに返す(`RetrySuppression.CIRCUIT_OPEN`)。
  - Circuit Breaker が断った呼び出し(`CircuitOpen`)と、Bulkhead が断った呼び出し(`BulkheadFull`)は、リトライしない。どちらも手元の判断で、待っても依存先の状態は分からないため。
  - **Bulkhead の拒否は、Circuit Breaker の失敗にもリトライバジェットにも数えず、リトライもしない。** 拒否は自分の側の混雑(同時実行数の上限)で、依存先の障害ではない。
    - 失敗に数えると、依存先が健全でも、呼び出し側の混雑だけで遮断してしまう。
    - リトライすると、混雑している自分の側に、待ちの呼び出しをさらに積むことになる。リトライバジェットの範囲内でだけリトライする案もあるが、バジェットは依存先の失敗率に合わせて減るもので、自分の側の混雑では減らない。混雑している間はリトライが続いてしまうため採らない。
    - 待ってもよい場合は、Bulkhead の `maxWait`(締め切りの残り時間までに縮める)で待つ。拒否は呼び出し元にすぐ返し、Fallback(`whenUnavailable` の対象)か、上位の判断に任せる。
- **リトライの待ち時間と回数は、kernel の `RetryPolicy.decide` をそのまま使う。** 同じ計算を `shared/resilience` に作らない。Jitter の乱数(`Random`)は `Resilience` に注入する。
- **RetryPolicy は呼び出しごとに上書きできる**(`execute(retry = ...)`)。冪等でない呼び出しは `null` を渡してリトライを止める。依存先ごとの `Resilience` を分けずに済むので、Circuit Breaker とリトライバジェットの状態は同じ依存先で 1 つのまま保てる。
- **リトライする呼び出しは冪等にする**(Framework 5.5・13.1「冪等前提」)。タイムアウトは `withTimeoutOrNull` で行うので、呼び出しが期限の直前に成功して戻っても、期限切れとして扱われてリトライされることがある。POST は `Idempotency-Key` を付ける。
- リトライを見送る判定は、Circuit Breaker の `state` が OPEN かどうかだけを見る。Half-Open で試す枠が埋まっている場合は見送らず、待った後に `CircuitOpen` で断られる(待ち時間が無駄になるが、Half-Open は短い)。Open の期間が過ぎた後、次の呼び出しまでは OPEN のままなので、そのときもリトライを見送る。

### 2. エラー
部品が返すエラーは `ResilienceError`(`DomainError.Retryable`)にまとめる。メッセージには依存先の名前と設定値だけを入れる。

| エラー | code | 意味 | Circuit Breaker | リトライ |
|---|---|---|---|---|
| `AttemptTimedOut` | `timeout` | 1 回の試行が `attemptTimeout` を超えた | 失敗に数える | する |
| `DeadlineExceeded` | `deadline_exceeded` | 呼び出し全体が締め切り(タイムバジェット)を超えた。`source` は予算を決めたもの(`OWN`: 自分の締め切り / `CALLER`: 呼び出し元の締め切り。§12) | `OWN` で試行を打ち切ったときは失敗に数える。`CALLER` で打ち切ったとき(§12)と、残り時間が 0 以下で呼び出さなかったときは数えない | しない |
| `CircuitOpen` | `circuit_open` | 遮断中。`retryAfter` は Open が明けるまでの残り時間(Half-Open で枠が埋まっているときは `null`) | 数えない | しない |
| `BulkheadFull` | `bulkhead_full` | 同時実行数の上限 | 数えない | しない |

`CircuitOpen` と `BulkheadFull` は `ResilienceRejection`(依存先に送らずに断った呼び出し)とする。

`DeadlineExceeded` も Retryable にする。依存先が一時的に遅いことを表し、入口では 503 などに写すため。同じ要求の予算は使い切っているので、その `Resilience` の中ではリトライしない。上位(別の `Resilience` やメッセージの消費側)がリトライするかは、上位の予算で判断する。

### 3. 失敗として数える範囲
Circuit Breaker とリトライバジェット(§8)は、1 回の試行の結果を次のとおり数える。

| 結果 | 数え方 | 理由 |
|---|---|---|
| Ok | 成功 | |
| Retryable なエラー(`AttemptTimedOut` と、自分の締め切りの `DeadlineExceeded` を含む) | 失敗 | 依存先の一時的な障害を表す |
| 呼び出し元の締め切りで打ち切った `DeadlineExceeded`(`source = CALLER`) | 数えない | 依存先の状態ではなく、呼び出し元の残り時間を表す(§12) |
| NonRetryable なエラー(4xx・業務エラー・契約違反) | 成功 | 依存先は応答している。呼び出し側の誤りで遮断すると、ほかの正しい呼び出しまで止まる |
| `ResilienceRejection` | 数えない | 手元の判断で、依存先の状態を表さない |
| 例外・キャンセル | 数えない | 呼び出しは `Result` で失敗を返す約束(境界の例外は `catching` で変換する)。例外は約束の外なので、依存先の失敗とみなさない。Half-Open の枠は返す |

呼び出し([block])の中で別の `Resilience` を使っていて、その `ResilienceRejection`(内側の依存先の遮断など)がそのまま返ってきた場合も、外側では数えず、リトライもしない。外側の依存先の状態ではないため。外側で別の扱いにしたいときは、呼び出しの中で別のエラーに写す。

HTTP の 500 は、INTEGRATION_STANDARDS §3 でリトライの対象外(NonRetryable)なので、Circuit Breaker の失敗にも数えない。502 / 503 / 504・接続エラー・タイムアウトは Retryable なので数える。HTTP の応答の分類は ② の `platform/reliability` の `HttpCallClassifier` で行う(§11)。

### 4. Circuit Breaker
| 設定 | 既定値 | 意味 |
|---|---|---|
| `window` | `Count(20)` | 失敗率を判定する窓。直近の件数(`Count`)か、直近の時間(`Time`。区間ごとに集計し、記憶量は一定) |
| `failureRateThreshold` | 0.5 | この割合以上が失敗なら Open |
| `minimumCalls` | 10 | 窓の中の件数がこれに満たないうちは判定しない |
| `openDuration` | 30 秒 | Open を続ける時間 |
| `halfOpenPermits` | 3 | Half-Open で試す件数。全件が成功すれば Closed、1 件でも失敗すれば Open |

- **Open から Half-Open への遷移は、`openDuration` が過ぎた後の最初の呼び出しで行う。** タイマーは使わない。呼び出しがない間は Open のまま(`state` も OPEN を返す)。
- **並行性**: 状態は `Mutex` で守る。遷移のたびに世代を進め、前の世代で許可した呼び出しの結果は数えない。Open の前に始まり、Half-Open の後に返った遅い呼び出しで、Half-Open の判定が狂わないようにするため。
- **Half-Open の枠**: 同時に `halfOpenPermits` 件までを通し、それを超える呼び出しは `CircuitOpen(retryAfter = null)` で断る。例外やキャンセルで終わった試行は、枠を返す。
- 枠の返却はキャンセルされた後でも行う(`NonCancellable`)。
- 状態の遷移はリスナー(§7)に知らせる。リスナーはロックの外で呼ぶ。Open から Half-Open への遷移は、枠を取った後に `try` の中で知らせる。リスナーが例外を投げても、`finally` で枠を返せるようにするため(返さないと、Half-Open のまま抜けられなくなる)。

### 5. Timeout と Bulkhead
- **Timeout** は coroutines のタイムアウト(`withTimeoutOrNull`)で行う。
  - `withTimeoutOrNull` は、自分の期限切れだけを `null` にする。呼び出し側のキャンセル(外側の `withTimeout` を含む)と、呼び出しの中の別のタイムアウトの `TimeoutCancellationException` は、捕まえずにそのまま伝える(`ResilienceSpec` で確かめた)。
  - `withTimeout` の例外を `catch` で捕まえる書き方は、自分の期限切れと中の別のタイムアウトを区別できないため使わない。
  - `attemptTimeout` は必須(Framework 13.1: 全呼出しに明示設定)。
- **Bulkhead** は `Semaphore` で同時実行数の上限を持つ。空きを待つ時間の上限 `maxWait` の既定は 0(待たずに `BulkheadFull`)で、正の値のときはその間だけ待つ。既定では付けない(`bulkhead = null`)。多くの依存先を持つサービスが、依存先ごとに設定する。

- Bulkhead の空き待ちでは、許可を取れたかを `withTimeoutOrNull` の戻り値で判断しない。`acquire()` が許可を取って戻った直後に期限が来ると、`withTimeoutOrNull` は `null` を返し、取った許可が返されずに漏れるため(kotlinx.coroutines のドキュメント "Asynchronous timeout and resources")。`acquire()` の直後に、中断しない代入で取得を記録する。

### 6. 時間は単調な `TimeSource` で測る
- 締め切りと Circuit Breaker の Open の期間は、注入した `TimeSource.WithComparableMarks`(既定は `TimeSource.Monotonic`)で測る。
- ADR-0004 §5 は「`Clock` をインジェクションする」としているが、壁時計(`kotlin.time.Clock`)は NTP の補正や手動の変更で巻き戻りうる。巻き戻ると Open が長引き、進むとすぐ明けてしまう。経過時間を測る部品には単調な時刻が適している。
- 業務の時刻(記録の時刻・期限の判定)は、これまでどおり `Clock` を使う(ADR-0011)。
- テストでは、kotest の `coroutineTestScope` の仮想時間の `testScheduler.timeSource` を渡す。`delay` と `withTimeoutOrNull` も同じ仮想時間で進むので、実時間の sleep に頼らずに、jvm / js / linuxX64 / macosArm64 で同じテストを動かせる。
- 同じ `context` の中のテストは仮想時間を共有するため、時刻は各テストの開始からの経過で比べる。
- 仮想時間のテストはシングルスレッドで動き、競合を再現しない。そのため、jvmTest に実際のスレッド(`Dispatchers.Default`)で並列に呼ぶテスト(`ConcurrencyStressSpec`)を置く。

### 7. 出来事はリスナーで外へ知らせる
`ResilienceListener` で、リトライ・リトライの見送り(理由つき)・Circuit Breaker の遷移・拒否・タイムアウト・Fallback を知らせる。
`shared/resilience` は OTel に依存しない(ADR-0004 §4)。② の `platform/reliability` の `ResilienceMetrics` がリスナーを OTel のメトリクスに写す。

| メトリクス | 種類 | 属性(依存先の名前 `eia.dependency.name` のほか) |
|---|---|---|
| `eia.resilience.circuit_breaker.state` | gauge | `state`(`closed` / `open` / `half_open`)。現在の状態だけ 1、ほかは 0 |
| `eia.resilience.circuit_breaker.transitions` | counter | `from`・`to` |
| `eia.resilience.retries` | counter | なし |
| `eia.resilience.retries.suppressed` | counter | `reason`(`deadline` / `circuit_open` / `budget_exhausted`) |
| `eia.resilience.retry_budget.tokens` | gauge | なし。リトライバジェットの残高(`Resilience.retryBudgetTokens`) |
| `eia.resilience.rejections` | counter | `kind`(`circuit_open` / `bulkhead_full`) |
| `eia.resilience.timeouts` | counter | `kind`(`attempt` / `deadline` / `caller_deadline`)。`caller_deadline` は呼び出し元の締め切りで打ち切った試行(§12) |
| `eia.resilience.fallbacks` | counter | なし |

- 属性は依存先の名前と、上の決まった値だけにする(カーディナリティ対策)。エラーのメッセージ・URL・ステータスは入れない。依存先の名前には、依存先ごとに決まった値を使う。
- `state` / `from` / `to` / `reason` / `kind` は名前空間を付けない。メトリクスの名前(`eia.resilience.*`)の中でだけ意味を持つ属性で、既存の `eia.security.jwt.rejections` の `reason` と揃える。ほかのメトリクスと共有する依存先の名前だけ、`eia.dependency.name` と名前空間を付ける。
- gauge は、`ResilienceMetrics.register` で登録した `Resilience` の状態を、収集のたびに読む。リトライバジェットの残高を読むため、`Resilience` に読み取り専用の `retryBudgetTokens` を公開する(ロックを取らずに読む最新の値)。
- **同じ名前の `Resilience` の 2 回目の登録は例外にする。** 依存先ごとに 1 つを使い回す約束を破って、呼び出しごとに作っている誤りを見つけるため。呼び出しごとに作ると、Circuit Breaker とリトライバジェットの状態が捨てられ、障害中も遮断されない。

### 8. リトライバジェットを入れ、既定で有効にする
Circuit Breaker のしきい値(既定 50%)を下回る失敗率が長く続くと、Circuit Breaker は開かないまま、リトライで依存先への要求が最大 maxAttempts 倍(既定 3 倍)に増え続ける(Retry Storm。Framework 13.3 のアンチパターン)。これを防ぐため、gRPC の retry throttling(gRFC A6)と同じトークンバケットを入れる。

- 残高は `maxTokens`(既定 10)から始まる。Retryable な失敗のたびに 1 減らし、成功のたびに `tokenRatio`(既定 0.1)増やす。
- 残高が `maxTokens` の半分以下の間は、リトライしない(`RetrySuppression.BUDGET_EXHAUSTED`)。初回の試行はいつも行う。
- 失敗が続くと、リトライの割合はおよそ `tokenRatio` まで下がる。障害がないときは、残高は上限に張り付いていて何もしない。
- 数え方は Circuit Breaker と同じ(§3)。
- `tokenRatio` は小数 3 桁までの精度で扱う(gRFC A6 と同じ)。
- 既定で有効にする。無効にするときは `retryBudget = null` を明示する。

### 9. Fallback は呼び出し側が明示的に渡す
- Fallback に暗黙の既定値は持たせない。代替動作(キャッシュ応答・既定値・縮退・後回し)は業務ごとに決めるもので、`Resilience.execute(fallback, ...)` に `Fallback` を渡したときだけ使う。
- 対象とするエラーも呼び出し側が選ぶ(`appliesTo`)。業務エラー(NonRetryable)を代替で隠さないよう、よく使う形として「Retryable のとき」(`Fallback.whenUnavailable`)を用意する。

### 10. 依存と Konsist
- `shared/resilience` の commonMain に `kotlinx-coroutines-core` を追加する。ADR-0010 Decision 6 の許可リストに、`shared/resilience` だけ `kotlinx.coroutines` を加える(kernel と canonical-model は変えない)。
- commonTest に `kotlinx-coroutines-test` を追加する(版は coroutines と同じ 1.11.0)。

### 11. ② の結線(`platform/reliability` と トークンの取得)
- **`HttpCallClassifier`**(INTEGRATION_STANDARDS §3): Ktor Client の結果を分類する。
  - 408・429・502・503・504 と、接続の失敗(`IOException`・名前解決の失敗)・タイムアウト(Ktor の HttpTimeout・接続と読み取りのタイムアウト)は Retryable(`HttpCallUnavailable`)。
  - 429 と 503 は `Retry-After`(秒数か HTTP-date)を `retryAfter` に入れる。
  - それ以外の 4xx・5xx(500 を含む)は NonRetryable(`HttpCallRejected`)。500 を Retryable にしたい依存先は `retryableStatuses` に加える。
  - キャンセルとそのほかの例外は捕まえずに伝える(§3: 例外は依存先の失敗とみなさない)。Ktor 3 の `HttpRequestTimeoutException` はキャンセルではないことを、実際の接続のテストで確かめた。
  - 本文の読み取りまでを 1 回の試行に含める(試行の Timeout が本文の読み取りに効くように)。
  - `Retry-After` の解析(`RetryAfter`)は `platform/security` から移し、両方で使う。
- **`ClientCredentialsTokenProvider`**(ADR-0019 §4):
  - 構築時に受け取った(または既定で作った)**1 つの `Resilience` を、すべての取得で使う。** 既定は名前 `oauth-token-endpoint`、試行 5 秒・締め切り 10 秒、Retry・リトライバジェット・Circuit Breaker は既定値。
  - 1 回の取得のタイムアウトは `Resilience` の `attemptTimeout` で行う(`ClientCredentialsConfig.timeout` はなくした。Timeout を二重にしないため)。
  - Secret の取得は `Resilience` の外で、取得ごとに 1 回行う。Secret の失敗を IdP の失敗として Circuit Breaker に数えないため。
  - 接続の失敗として扱う例外は `HttpCallClassifier` と同じ(`IOException`・名前解決の失敗・タイムアウト)。そのほかの例外は捕まえずに伝える(§3)。
  - 戻り値の型(`Result<AccessToken, TokenError>`)は変えない。`ResilienceError` は `TokenEndpointUnavailable` に写す(`timeout` / `deadline_exceeded` / `circuit_open` / `bulkhead_full`。`circuit_open` は Open が明けるまでの時間を `retryAfter` に持つ)。
  - IdP の 5xx(500 を含む)は、ADR-0019 §4 のとおり Retryable のままにする。トークンの取得は、依存先ごとに 500 を一時的な障害として扱う例(§3 と Consequences)にあたる。
  - Fallback は、既存の「期限前の取り直しに失敗したら、期限内のトークンを使い続ける」処理をそのまま使う(`Fallback` の型は使わない。キャッシュの状態と一体のため)。
- **呼び出し元の締め切りの引き継ぎ**は、② の時点では入れず、P05 で §12 として入れた(Issue #48)。
- **platform のモジュール間の依存は、許可した一覧だけにする**(Konsist の `PlatformDependencyRules`。MODULE_DESIGN §2)。`platform/security` → `platform/reliability` を加える。

### 12. 呼び出し元の締め切りを、入れ子の `Resilience` に引き継ぐ(P05。Issue #48)
Framework 13.1 のタイムバジェットは、入口に配分した時間を、その内側のすべての呼び出しで分け合う。§1 の締め切りは `execute` の引数だけで受け取るため、入れ子の `Resilience`(API の処理の途中のトークンの取得など)は、入口の予算を超えて待ちえた。

- **締め切りはコルーチンのコンテキストで運ぶ**(`CallDeadline`。`CoroutineContext.Element`)。KMP の commonMain で使え、呼び出しの引数を増やさずに、関数の境界を越えて届く。
  - 入口(API のハンドラなど)は `withCallDeadline(budget) { ... }` で置く。すでに締め切りがあり、その残り時間が `budget` 以下なら、それを保つ(内側で延ばせない)。
  - `CallDeadline` は期限を知らせるだけで、打ち切らない。打ち切るのは `Resilience`(や呼び出し側の `withTimeout`)。
  - 期限は作った側の `TimeSource` の `TimeMark` で持つ。読む側の `Resilience` が別の `TimeSource`(テストの仮想時間など)を使っていても、残り時間は期限を作った側の時刻で測る。
- **`Resilience` の締め切りは、自分の締め切り(`deadline` の引数か設定)と、呼び出し元の `CallDeadline` の残り時間の短い方にする。**
  - どちらもなければ締め切りなし。延ばすことはできない。
  - 呼び出し元の期限を過ぎていれば予算を 0 にし、§1 のとおり呼び出さずに `DeadlineExceeded` を返す(Circuit Breaker に数えない)。
  - `DeadlineExceeded.deadline` は、この短い方の予算を表す。
- **`Resilience` は、試行の block に、その試行の残り時間を `CallDeadline` で渡す。** 残り時間は、試行の Timeout(`min(attemptTimeout, 締め切りの残り時間)`)。
  - これで、block の中の `Resilience` は、外側の試行が打ち切られる時刻を超えて待たない。
  - 内側のリトライの待ちが外側の残り時間を超えるなら、待たずに見送る(`RetrySuppression.DEADLINE`)。外側に打ち切られてキャンセルされるのを待たずに、エラーを外側に返せる。
- **呼び出し元の締め切りで、`attemptTimeout` より前に打ち切った試行は数えない。**
  - `DeadlineExceeded.source` で、予算を決めたものを区別する。呼び出し元の `CallDeadline` の残り時間が自分の締め切りより短ければ `CALLER`、そうでなければ `OWN`(同じなら `OWN`)。
  - 打ち切りの理由ごとの数え方:

    | 打ち切りの理由 | エラー | Circuit Breaker・リトライバジェット |
    |---|---|---|
    | 試行が `attemptTimeout` に達した(呼び出し元の残り時間が `attemptTimeout` 以上だった場合を含む) | `AttemptTimedOut` | 失敗に数える |
    | 自分の締め切り(設定の `deadline` か `execute` の引数)の残り時間で打ち切った | `DeadlineExceeded(source = OWN)` | 失敗に数える(§1) |
    | 呼び出し元の締め切りの残り時間で打ち切った | `DeadlineExceeded(source = CALLER)` | 数えない(判定の窓に入れない。リトライバジェットも減らさない) |
    | 呼び出す前に残り時間が 0 以下だった | `DeadlineExceeded`(どちらの `source` でも) | 数えない(§1) |

  - **数えない理由**: 呼び出し元の残り時間は、依存先ではなく、呼び出し元の状態で決まる。高負荷で入口の待ちや上流の処理が長引くと、残り時間が減る。その残り時間で打ち切った試行を失敗に数えると、健全な依存先への回路まで開き、Retry を止め、負荷の上昇を障害に増幅する。依存先が本当に遅い(ハングしている)ことは、`attemptTimeout` に達した試行と、自分の締め切りで打ち切った試行で判断する。
  - 残り時間が `attemptTimeout` 以上なら、打ち切りは `attemptTimeout` によるものとする。試行は依存先に与えた時間を使い切っているので、呼び出し元の予算が短かったことは理由にならない。
  - **Half-Open**: 試す枠を取った試行が、呼び出し元の締め切りで打ち切られた場合は、ほかの数えない結果(例外・キャンセル)と同じく、枠を返して Half-Open のままにする(§4)。次の呼び出しが試す。成功とも失敗とも判断できない試行で、Closed にも Open にも遷移させないため。
  - **メトリクス**: 数えない代わりに、`eia.resilience.timeouts` の `kind=caller_deadline` で数える(§7)。予算の配分が短すぎないかを、この件数で見る。
  - **入れ子**: 内側の `Resilience` は、外側の試行の残り時間を `CallDeadline` として受け取る。そのため、外側の試行の時間が尽きると、内側は `CALLER` で打ち切られる。外側は、その結果を自分の試行の時間切れ(`attemptTimeout` か自分の締め切り。§1 のとおり数える)として扱う。外側と内側のタイムアウトは同じ時刻に来るので、どちらが先に返っても同じ結果にするため。外側の試行の時間が残っているのに内側が `CALLER` で打ち切られた場合(block の中で `withCallDeadline` で縮めた場合)は、外側でも数えない。
- **`ClientCredentialsTokenProvider` での扱い**(ADR-0019 §4):
  - 取得の締め切りは、上の規則で呼び出し元の残り時間に縮む。
  - 取得中の呼び出しを待つ時間(`Mutex`)も、呼び出し元の残り時間までにする。待ちきれなければ `deadline_exceeded` を返す。ロックを取れたかは Bulkhead と同じく `withTimeoutOrNull` の戻り値で判断しない(§5)。
  - 呼び出し元の締め切りで打ち切った取得の失敗(`DeadlineExceeded.source = CALLER`)は、待っていた呼び出しに共有しない。その失敗は、取得した呼び出しの予算によるもので、待っていた呼び出しは自分の残り時間で取り直せるため。
  - 設定の締め切りで打ち切った失敗と、そのほかの失敗は、これまでどおり共有する(IdP の障害中に「待ち数 × 締め切り」待たせない。ADR-0019 §4)。

## Alternatives Considered
- **Resilience4j を使う / Arrow(arrow-resilience)を使う**: ADR-0004 で不採用(native / js で使えない。`Either` と kernel の `Result` が併存する)。
- **重ねる順序を利用者に組ませる(デコレータを自由に重ねる)**: 柔軟だが、Retry の外側に Circuit Breaker を置く(リトライの失敗が 1 件にしか数えられない)などの誤りを防げない。全社のテンプレートとしては、順序を固定して設定だけを選ばせるほうがよい。不採用。
- **`CircuitOpen` をリトライし、`retryAfter`(Open が明けるまでの残り時間)だけ待つ**: RetryPolicy は `retryAfter` を優先するので実装は簡単だが、呼び出し側を Open の期間(既定 30 秒)まで待たせ、タイムバジェットを食い潰す。遮断の目的(速く失敗させる)にも反する。不採用。
- **NonRetryable も Circuit Breaker の失敗に数える**: 呼び出し側の入力の誤り(400)が続いただけで遮断し、ほかの正しい呼び出しまで止めてしまう。不採用。
- **Open から Half-Open への遷移をタイマーで行う**: 呼び出しがなくても状態が変わりメトリクスは正確になるが、依存先ごとにタイマーのコルーチンとそのスコープの管理が要る。最初の呼び出しで遷移すれば足りる。不採用。
- **壁時計(`Clock`)で測る**: §6 のとおり巻き戻りの影響を受ける。不採用。
- **リトライバジェットを入れない(Circuit Breaker と maxAttempts の上限で足りるとする)**: 増幅は最大 3 倍に抑えられるが、しきい値を下回る失敗率が続く状況では 3 倍の負荷が続く。不採用。
- **締め切りを `execute` の引数で明示的に渡し続ける(§12 を入れない)**: 呼び出しの途中の層(トークンの取得のように、呼び出し元が `Resilience` を持っていることを知らない部品)まで、すべての関数に残り時間の引数が要る。渡し忘れると、その先は入口の予算を超えて待つ。不採用。
- **`CallDeadline` を `withTimeout` で打ち切る要素にする**: 締め切りを置いただけで、置いた範囲の処理(DB の更新の後の応答など)が途中でキャンセルされる。打ち切りの場所は `Resilience` の試行に限り、`CallDeadline` は知らせるだけにした。不採用。
- **呼び出し元の締め切りで打ち切った試行も、Circuit Breaker の失敗に数える(§1 と同じ扱い)**: ハングした依存先に、予算の短い呼び出しだけが続く場合でも遮断できる。しかし、高負荷で残り時間が減ると、健全な依存先への回路まで開き、負荷を障害に増幅する。不採用。
- **呼び出し元の締め切りで打ち切った試行を、成功として数える**: 窓の失敗率が下がり、本当に遅い依存先の遮断が遅れる。成功とも失敗とも判断できない試行なので、数えない。不採用。
- **時間あたりのリトライの割合で上限を設ける(Finagle の RetryBudget)**: 時間の窓の管理が要る。gRPC の方式は時間に依存せず、状態も整数 1 つで済む。不採用。

## Consequences(トレードオフ)
- 重ねる順序を固定したため、例外的な順序(Retry の外側に Circuit Breaker を置くなど)が必要な連携では、部品(`CircuitBreaker` / `Bulkhead`)を直接使って組む必要がある。部品は単独でも公開している。
- HTTP の 500 は Circuit Breaker の失敗に数えないので、500 を返し続ける依存先は遮断されない。500 を一時的な障害として扱いたい依存先は、② の HTTP の分類で Retryable に写す(依存先ごとの判断)。
- リトライバジェットを既定で有効にしたため、障害が続くと、RetryPolicy が認めたリトライの一部が見送られる。見送りは `RetrySuppression.BUDGET_EXHAUSTED` として、リスナー(② でメトリクス)で見える。
- Open の期間が過ぎても、次の呼び出しまで `state` は OPEN のまま。メトリクスの gauge は、呼び出しがない間は Open を示し続ける。
- 並行性は `Mutex` で守るので、呼び出しごとにロックを 2 回(許可と記録)取る。依存先への呼び出し(ミリ秒単位)に比べて小さいとみなす。
- ADR-0004 §5 の「`Clock` をインジェクション」は、経過時間については `TimeSource` と読み替える(ADR-0004 の改訂履歴に記録する)。
- 呼び出し元の締め切りで打ち切った試行を数えない(§12)ため、依存先への呼び出しが、すべて予算の短い呼び出し(残り時間が `attemptTimeout` より短い)である状況では、ハングした依存先でも Circuit Breaker は開かない。この間、各呼び出しが待つのは自分の残り時間までで、待ちの上限は入口の予算で決まる。遮断の判断は、`attemptTimeout` に達した試行と自分の締め切りで打ち切った試行でだけ行う。予算の配分が短すぎないかは、`kind=caller_deadline` の件数で見る。
- Half-Open の試行が呼び出し元の締め切りで打ち切られ続けると、Half-Open のまま(`halfOpenPermits` 件ずつ試す)になる。遮断は解けないが、依存先に送る同時の呼び出しは `halfOpenPermits` 件までに抑えられる。
- `ClientCredentialsConfig.timeout` をなくしたため、トークンの取得の Timeout を変えるときは、`Resilience` を作って `ClientCredentialsTokenProvider` に渡す。

## 改訂履歴
- 2026-09-30: ② の決定を追記した(§3 の分類の置き場所、§7 のメトリクスの一覧と `retryBudgetTokens`・重複登録の検出、§11 の結線)。
- 2026-09-30: §12(呼び出し元の締め切りの引き継ぎ。`CallDeadline`)を追記した(P05 ①。Issue #48)。§11 と Consequences の「引き継がない」を改めた。呼び出し元の締め切りで打ち切った試行は Circuit Breaker とリトライバジェットに数えず(`DeadlineExceeded.source`)、メトリクスの `kind=caller_deadline` で数える(§1・§2・§3・§7 に反映した)。
