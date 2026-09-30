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

- **締め切りの残り時間を超えて待つリトライはしない。** RetryPolicy が決めた待ち時間が残り時間以上なら、待たずにその時点のエラーを返す(`RetrySuppression.DEADLINE`)。締め切りは設定(`ResilienceConfig.deadline`)で決め、呼び出しごとに上書きできる(入口から配分された残り時間を渡すため)。
- **Circuit Breaker が開いたら、リトライせずにすぐ返す。**
  - リトライの待機の前に状態を見て、Open なら待たずに返す(`RetrySuppression.CIRCUIT_OPEN`)。
  - Circuit Breaker が断った呼び出し(`CircuitOpen`)と、Bulkhead が断った呼び出し(`BulkheadFull`)は、リトライしない。どちらも手元の判断で、待っても依存先の状態は分からないため。
- **リトライの待ち時間と回数は、kernel の `RetryPolicy.decide` をそのまま使う。** 同じ計算を `shared/resilience` に作らない。Jitter の乱数(`Random`)は `Resilience` に注入する。

### 2. エラー
部品が返すエラーは `ResilienceError`(`DomainError.Retryable`)にまとめる。メッセージには依存先の名前と設定値だけを入れる。

| エラー | code | 意味 | Circuit Breaker | リトライ |
|---|---|---|---|---|
| `AttemptTimedOut` | `timeout` | 1 回の試行が `attemptTimeout` を超えた | 失敗に数える | する |
| `DeadlineExceeded` | `deadline_exceeded` | 呼び出し全体が締め切りを超えた | (試行は中断され、数えない) | しない(最も外側) |
| `CircuitOpen` | `circuit_open` | 遮断中。`retryAfter` は Open が明けるまでの残り時間(Half-Open で枠が埋まっているときは `null`) | 数えない | しない |
| `BulkheadFull` | `bulkhead_full` | 同時実行数の上限 | 数えない | しない |

`CircuitOpen` と `BulkheadFull` は `ResilienceRejection`(依存先に送らずに断った呼び出し)とする。

### 3. 失敗として数える範囲
Circuit Breaker とリトライバジェット(§8)は、1 回の試行の結果を次のとおり数える。

| 結果 | 数え方 | 理由 |
|---|---|---|
| Ok | 成功 | |
| Retryable なエラー(`AttemptTimedOut` を含む) | 失敗 | 依存先の一時的な障害を表す |
| NonRetryable なエラー(4xx・業務エラー・契約違反) | 成功 | 依存先は応答している。呼び出し側の誤りで遮断すると、ほかの正しい呼び出しまで止まる |
| `ResilienceRejection` | 数えない | 手元の判断で、依存先の状態を表さない |
| 例外・キャンセル | 数えない | 呼び出しは `Result` で失敗を返す約束(境界の例外は `catching` で変換する)。例外は約束の外なので、依存先の失敗とみなさない。Half-Open の枠は返す |

HTTP の 500 は、INTEGRATION_STANDARDS §3 でリトライの対象外(NonRetryable)なので、Circuit Breaker の失敗にも数えない。502 / 503 / 504・接続エラー・タイムアウトは Retryable なので数える。HTTP の応答の分類は ② の `platform/reliability` で行う。

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
- 状態の遷移はリスナー(§7)に知らせる。リスナーはロックの外で呼ぶ。

### 5. Timeout と Bulkhead
- **Timeout** は coroutines のタイムアウト(`withTimeoutOrNull`)で行う。
  - `withTimeoutOrNull` は、自分の期限切れだけを `null` にする。呼び出し側のキャンセル(外側の `withTimeout` を含む)と、呼び出しの中の別のタイムアウトの `TimeoutCancellationException` は、捕まえずにそのまま伝える(`ResilienceSpec` で確かめた)。
  - `withTimeout` の例外を `catch` で捕まえる書き方は、自分の期限切れと中の別のタイムアウトを区別できないため使わない。
  - `attemptTimeout` は必須(Framework 13.1: 全呼出しに明示設定)。
- **Bulkhead** は `Semaphore` で同時実行数の上限を持つ。空きを待つ時間の上限 `maxWait` の既定は 0(待たずに `BulkheadFull`)で、正の値のときはその間だけ待つ。既定では付けない(`bulkhead = null`)。多くの依存先を持つサービスが、依存先ごとに設定する。

### 6. 時間は単調な `TimeSource` で測る
- 締め切りと Circuit Breaker の Open の期間は、注入した `TimeSource.WithComparableMarks`(既定は `TimeSource.Monotonic`)で測る。
- ADR-0004 §5 は「`Clock` をインジェクションする」としているが、壁時計(`kotlin.time.Clock`)は NTP の補正や手動の変更で巻き戻りうる。巻き戻ると Open が長引き、進むとすぐ明けてしまう。経過時間を測る部品には単調な時刻が適している。
- 業務の時刻(記録の時刻・期限の判定)は、これまでどおり `Clock` を使う(ADR-0011)。
- テストでは、kotest の `coroutineTestScope` の仮想時間の `testScheduler.timeSource` を渡す。`delay` と `withTimeoutOrNull` も同じ仮想時間で進むので、実時間の sleep に頼らずに、jvm / js / linuxX64 / macosArm64 で同じテストを動かせる。
- 同じ `context` の中のテストは仮想時間を共有するため、時刻は各テストの開始からの経過で比べる。

### 7. 出来事はリスナーで外へ知らせる
`ResilienceListener` で、リトライ・リトライの見送り(理由つき)・Circuit Breaker の遷移・拒否・タイムアウト・Fallback を知らせる。
`shared/resilience` は OTel に依存しない(ADR-0004 §4)。② の `platform/reliability` がリスナーを OTel のメトリクスに写す。
- Circuit Breaker の状態(gauge)
- リトライの回数
- Bulkhead で拒否した件数

属性は依存先の名前など、数が限られるものだけにする(カーディナリティ対策)。

### 8. リトライバジェットを入れ、既定で有効にする
Circuit Breaker のしきい値(既定 50%)を下回る失敗率が長く続くと、Circuit Breaker は開かないまま、リトライで依存先への要求が最大 maxAttempts 倍(既定 3 倍)に増え続ける(Retry Storm。Framework 13.3 のアンチパターン)。これを防ぐため、gRPC の retry throttling(gRFC A6)と同じトークンバケットを入れる。

- 残高は `maxTokens`(既定 10)から始まる。Retryable な失敗のたびに 1 減らし、成功のたびに `tokenRatio`(既定 0.1)増やす。
- 残高が `maxTokens` の半分以下の間は、リトライしない(`RetrySuppression.BUDGET_EXHAUSTED`)。初回の試行はいつも行う。
- 失敗が続くと、リトライの割合はおよそ `tokenRatio` まで下がる。障害がないときは、残高は上限に張り付いていて何もしない。
- 数え方は Circuit Breaker と同じ(§3)。
- 既定で有効にする。無効にするときは `retryBudget = null` を明示する。

### 9. Fallback は呼び出し側が明示的に渡す
- Fallback に暗黙の既定値は持たせない。代替動作(キャッシュ応答・既定値・縮退・後回し)は業務ごとに決めるもので、`Resilience.execute(fallback, ...)` に `Fallback` を渡したときだけ使う。
- 対象とするエラーも呼び出し側が選ぶ(`appliesTo`)。業務エラー(NonRetryable)を代替で隠さないよう、よく使う形として「Retryable のとき」(`Fallback.whenUnavailable`)を用意する。

### 10. 依存と Konsist
- `shared/resilience` の commonMain に `kotlinx-coroutines-core` を追加する。ADR-0010 Decision 6 の許可リストに、`shared/resilience` だけ `kotlinx.coroutines` を加える(kernel と canonical-model は変えない)。
- commonTest に `kotlinx-coroutines-test` を追加する(版は coroutines と同じ 1.11.0)。

## Alternatives Considered
- **Resilience4j を使う / Arrow(arrow-resilience)を使う**: ADR-0004 で不採用(native / js で使えない。`Either` と kernel の `Result` が併存する)。
- **重ねる順序を利用者に組ませる(デコレータを自由に重ねる)**: 柔軟だが、Retry の外側に Circuit Breaker を置く(リトライの失敗が 1 件にしか数えられない)などの誤りを防げない。全社のテンプレートとしては、順序を固定して設定だけを選ばせるほうがよい。不採用。
- **`CircuitOpen` をリトライし、`retryAfter`(Open が明けるまでの残り時間)だけ待つ**: RetryPolicy は `retryAfter` を優先するので実装は簡単だが、呼び出し側を Open の期間(既定 30 秒)まで待たせ、タイムバジェットを食い潰す。遮断の目的(速く失敗させる)にも反する。不採用。
- **NonRetryable も Circuit Breaker の失敗に数える**: 呼び出し側の入力の誤り(400)が続いただけで遮断し、ほかの正しい呼び出しまで止めてしまう。不採用。
- **Open から Half-Open への遷移をタイマーで行う**: 呼び出しがなくても状態が変わりメトリクスは正確になるが、依存先ごとにタイマーのコルーチンとそのスコープの管理が要る。最初の呼び出しで遷移すれば足りる。不採用。
- **壁時計(`Clock`)で測る**: §6 のとおり巻き戻りの影響を受ける。不採用。
- **リトライバジェットを入れない(Circuit Breaker と maxAttempts の上限で足りるとする)**: 増幅は最大 3 倍に抑えられるが、しきい値を下回る失敗率が続く状況では 3 倍の負荷が続く。不採用。
- **時間あたりのリトライの割合で上限を設ける(Finagle の RetryBudget)**: 時間の窓の管理が要る。gRPC の方式は時間に依存せず、状態も整数 1 つで済む。不採用。

## Consequences(トレードオフ)
- 重ねる順序を固定したため、例外的な順序(Retry の外側に Circuit Breaker を置くなど)が必要な連携では、部品(`CircuitBreaker` / `Bulkhead`)を直接使って組む必要がある。部品は単独でも公開している。
- HTTP の 500 は Circuit Breaker の失敗に数えないので、500 を返し続ける依存先は遮断されない。500 を一時的な障害として扱いたい依存先は、② の HTTP の分類で Retryable に写す(依存先ごとの判断)。
- リトライバジェットを既定で有効にしたため、障害が続くと、RetryPolicy が認めたリトライの一部が見送られる。見送りは `RetrySuppression.BUDGET_EXHAUSTED` として、リスナー(② でメトリクス)で見える。
- Open の期間が過ぎても、次の呼び出しまで `state` は OPEN のまま。メトリクスの gauge は、呼び出しがない間は Open を示し続ける。
- 並行性は `Mutex` で守るので、呼び出しごとにロックを 2 回(許可と記録)取る。依存先への呼び出し(ミリ秒単位)に比べて小さいとみなす。
- ADR-0004 §5 の「`Clock` をインジェクション」は、経過時間については `TimeSource` と読み替える(ADR-0004 の改訂履歴に記録する)。
