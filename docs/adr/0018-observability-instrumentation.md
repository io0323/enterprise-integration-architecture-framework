# ADR-0018: 可観測性の計装方式・Correlation ID の伝搬・ログの経路
- Status: Accepted
- Date: 2026-09-27
- Framework 参照章: 12, 13, 14
- 関連: ADR-0003(ミドルウェア選定)、ADR-0004 §4・§5(OTel SDK の配置、traceparent を KMP に置く)、ADR-0016(ローカル基盤)
- 番号: P04a のサブ PR ごとに予約した番号(0017 audit / 0018 observability / 0019 security)。マージの順で一時的に番号が飛ぶ

## Context
P04a ② で `platform/observability` を作る。Framework 14 章は、全チャネルでの `traceparent` と Correlation ID の伝搬、構造化 JSON ログ、RED メトリクスを求め、ペイロード全文のログを禁じている。決める必要があるのは次の点。

1. OTel の計装を、Java エージェントで行うか、ライブラリ(コードで SDK を組み立てる)で行うか。P11 の KMP SDK(native / js)ではエージェントが使えないため、JVM と SDK で同じ伝搬の仕組み(`shared/resilience` の `TraceParent`)を使えるかを評価軸に入れる。
2. Correlation ID を W3C Baggage で運ぶか。
3. ログを Loki に届ける経路。
4. OTel の計装ライブラリの多くが alpha であることへの対応(P06・P07 の Kafka と JDBC を含む)。

## Decision
### 1. ライブラリ方式で計装し、Propagator は `TraceParent` を使う自前の実装にする
| 評価の観点 | Java エージェント | ライブラリ(採用) |
|---|---|---|
| P11 の KMP SDK(native / js)と同じ伝搬の仕組みを使えるか | 使えない。エージェントは OTel 自身の W3C 実装で伝搬する。JVM は OTel の実装、SDK は `TraceParent` という二重の実装になり、規則の差(下の表)がサービスと SDK の間で表に出る | 使える。`EiaTraceContextPropagator` が解析と生成に `TraceParent` を使うため、JVM と SDK が同じ規則で伝搬する |
| 計装の手間 | 少ない(Ktor・Kafka・JDBC を自動で計装する) | Ktor は自前のプラグイン(`ServerObservability` / `ClientObservability`)。Kafka と JDBC は §4 |
| テスト | バイトコードを書き換えるため、単体テストで確かめにくい | InMemory の exporter / reader で単体テストができる。Testcontainers の Collector で結合を確かめる(`CollectorIT`) |
| 起動と運用 | `-javaagent` の配布と指定が要り、起動が遅くなる。エージェントの版とアプリの依存の組み合わせを管理する | 設定はコード(`ObservabilityConfig`)に閉じる |
| 自動計装の範囲の把握 | 何が計装されるかがエージェントの版で変わる | 計装する箇所がコードで明示される |

- `Observability.init()` が SDK を組み立てる。`GlobalOpenTelemetry` には登録せず、`ObservabilityRuntime` を Koin などで明示的に渡す(テストで何度でも初期化できる。暗黙の依存を作らない)。
- Propagator は `EiaTraceContextPropagator` だけを登録する。
- 正しい `traceparent` / `tracestate` に対する抽出・注入の結果は、OTel 標準の `W3CTraceContextPropagator` と一致する。版 00 と Level 2 の定義済みフラグ(00〜03)について、`EiaTraceContextPropagatorSpec` の property test で検査している。
- 不正な入力の扱いは `TraceParent.parse` に従うため、OTel 標準と次の点が異なる(同じテストで固定している)。

  | 入力 | EIAF | OTel 標準(1.66.0) | 理由 |
  |---|---|---|---|
  | 前後の OWS(SP / HTAB) | 取り除いて受け付ける | 拒否する | RFC 9110 §5.6.3 はヘッダ値の前後の OWS を値に含めない |
  | 未定義の trace-flags(0x04 以上) | 0 にする(`ff` → `03`) | そのまま保つ | W3C Trace Context Level 2 §3.2.2.5.3「Vendors MUST set those to zero」(ADR-0004 §5) |
  | 未知の上位版の未定義フラグ | 同上 | 同上 | §4.1.2 |
  | そのほか(版 `ff`、大文字の 16 進、全 0 の ID、版 00 の拡張部、区切りの誤り) | 拒否する | 拒否する | 差はない |
  | `tracestate` の不正なメンバー・重複した key・33 個以上のメンバー・512 文字超 | 全体を捨て、`traceparent` は使う | 正しい入力では同じ結果。不正な入力は EIAF の規則で判断する | W3C Trace Context §3.3(tracestate)。長さの上限は、切り詰めずに全体を捨てる側に倒した |

- トレースの起点は SDK が作る。入口に `traceparent` がないとき、`TraceParent.generate()` で起点を作ると、実在しない親 span を指すことになるため。OTel Java SDK 1.66.0 は起点の span に random フラグ(0x02)を立て、子の span も引き継ぐことを確かめた(`KtorObservabilitySpec`)。`TraceParent.generate()` を使うのは、SDK(P11)と OTel を通らない経路である。
- span には、例外のイベント(`recordException`)を入れない。例外のメッセージは個人情報を含みうるため、型だけを `error.type` に残す。URL のパスとクエリも属性に入れない(`http.route` のテンプレートだけを使う)。

### 2. Correlation ID は `X-Correlation-Id` で運び、W3C Baggage は使わない
- 入口(`ServerObservability`)では、受信した `X-Correlation-Id` を `CorrelationId.parse` で検証して使う。ない・不正・複数あるときは `CorrelationId.generate()` で採番する(Framework 14.1)。不正な値は、値を出さずに DEBUG ログに残し、件数をメトリクスで数える(下記)。
- 決まった ID は、ログの MDC(`correlation_id`)と span の属性(`correlation_id`)の両方に入れ、レスポンスのヘッダにも返す。送信側(`ClientObservability`)は、呼び出し元のコルーチンの `ObservabilityContext` から付ける。
- MDC と OTel の Context は、`ObservabilityContext`(`ThreadContextElement`)がコルーチンの再開のたびに設定し、中断したら元に戻す。`kotlinx-coroutines-slf4j` の `MDCContext` を使わないのは、MDC と OTel の Context を 1 つの要素でまとめて切り替えたいため(別々の要素にすると、片方だけが入った状態を作れてしまう)。
- 子の span を現在にするときは `ObservabilityRuntime.withSpan(name) { ... }` を使い、OTel の `span.makeCurrent()` を中断(suspend)をまたいで使わない。`makeCurrent()` の Scope はスレッドに結び付くため、コルーチンが別のスレッドで再開すると、以降のログと送信の親がずれる。`withSpan` は `ObservabilityContext` の OTel の Context を差し替える。
- 送信側で呼び出し側が `X-Correlation-Id` を明示した場合は、正しい値ならそれを送り、span の属性にも同じ値を入れる。不正ならコンテキストの値に置き換える(送る値と span の値を食い違わせない)。
- 受信した `X-Correlation-Id` が不正なときのログは DEBUG にし、件数はメトリクス `eia.http.server.correlation_id.invalid` で数える。外部から不正な値を大量に送られても、ログが増えないようにするため。
- **W3C Baggage では運ばない。** Baggage の Propagator も登録しない。
  - INTEGRATION_STANDARDS §2 の標準は `X-Correlation-Id`(HTTP)/ `correlationid`(Kafka のヘッダ)/ manifest の `correlationId` である。Baggage に同じ値を載せると、どちらが正しい値か曖昧になる。
  - Baggage は、Propagator を通るすべての送信(外部の SaaS や取引先への呼び出しを含む)に自動で付く。業務の ID を意図せず社外に出すことになる(Framework 12 章の最小権限・情報の持ち出し)。`X-Correlation-Id` は、送信先ごとにプラグインを付けるかどうかで制御できる。
  - W3C Baggage にはサイズの上限があり、P11 の KMP SDK にも Baggage の実装が要る。

### 3. ログは logback から OTLP で送り、標準出力にも同じ内容を出す
| 観点 | logback から OTLP で送る(採用) | Collector がコンテナのログを読む(filelog / docker receiver) |
|---|---|---|
| trace_id / span_id | OTLP のログレコードの項目として届き、Loki で構造化メタデータになる | JSON を解析して取り出す設定が Collector に要る |
| ホストで動かしたアプリ(`./gradlew run`) | 同じ経路で届く | 届かない(コンテナのログではないため) |
| ローカル環境の前提 | なし | Collector に docker.sock かコンテナのログのディレクトリをマウントする必要がある。macOS の Docker Desktop では VM の中のパスになり、構成が環境で変わる |
| 統合テスト | Testcontainers の Collector で確かめられる | コンテナのログの収集まで再現する必要がある |
| Collector が止まったとき | 送れない分は欠ける(SDK のバッファの範囲) | ファイルが残るので後から読める |

- 欠けることへの対策として、**標準出力にも同じ内容を出す**(`EiaLogEncoder`)。既定は構造化 JSON で、`EIA_LOG_FORMAT=console` で人が読む 1 行の形式にする(不正な値は json にして警告する)。
- JSON の必須キーは CODING_STANDARDS「ロギング」のとおり: `timestamp`, `level`, `service`, `trace_id`, `span_id`, `correlation_id`, `integration_id`, `message`。値のないキーは `null` で出す。
- OTLP に送るアペンダ(`OtlpLogAppender`)は、OTel の安定版の Logs API で自前に実装する(計装ライブラリの `opentelemetry-logback-appender-1.0` は alpha のみ。§4)。
- **OTel の初期化より前のログ**: `OtlpLogAppender` は初期化(`Observability.init()`)まで最大 `bufferSize`(既定 1,000)件をメモリに溜め、初期化時に元の時刻のまま送る。溢れた分は捨て、捨てた件数を初期化時に WARN で送る。起動時のログ(設定の読み込み・マイグレーション)は障害の調査に要るが、無制限に溜めると初期化に失敗したときにメモリを使い続けるため、上限を設ける。標準出力には、初期化の前後によらず常に出る(`LoggingSpec` で検査)。
- 各サービスは `logback.xml` で `io/eia/platform/observability/logback-base.xml` を読み込む。
- **マスキングの位置づけ**: 主な対策は、本文をログに出さないことと、出す項目を許可リストで固定すること(プラグインは本文を読まず、ログに出す項目を method・route・status・duration などに固定している。書き手は受け取った値ではなく参照キーを書く)。正規表現による伏せ字は多層防御の 1 つであり、すべての漏れを防ぐことは目的としない。
- **マスキング**: `Masking` が、PEM の塊、URL の userinfo、秘密情報のキーの値、トークン(JWT・Bearer / Basic)、メールアドレス、カード番号(Luhn で確認)、電話番号を伏せる。`EiaLogEncoder` と `OtlpLogAppender` が、メッセージ・例外・MDC の値に必ず適用する。
  - PEM の塊(`-----BEGIN ...-----` から `-----END ...-----` まで)は、引用符の有無や改行の種類(`\n` / `\r\n` / JSON の `\n` エスケープ / 改行なし)によらず丸ごと `***` にする。閉じなければ入力の末尾まで伏せる。秘密情報のキーの規則より先に適用し、正規表現ではなく `indexOf` で走査する(入力長に比例する時間。`MaskingSpec` で、入力を 4 倍にしたときの時間の比を検査する)。
  - 秘密情報のキーは広めに照合する。大文字小文字と区切り(`_` / `-` / camelCase / `api key` のような空白)を問わず、`password` / `secret` / `token` / `api key` / `credential` / `authorization` / `cookie` などの語を含むキー(`pass` / `passphrase` は語全体のときだけ。`passed` や `bypass` は対象外)(`newPassword`、`clientSecret`、`x-api-key` など)の値を、引用符の有無によらず伏せる。値が配列やオブジェクト(2 段の入れ子まで)なら中身ごと `"***"` にする。
  - 語を含むだけで伏せるため、`tokenExpiresIn` や `passwordPolicy` のような運用上の値も伏せる。許可リストは設けない(許可リストの漏れは秘密情報の漏洩になり、伏せすぎは調査の手間で済むため)。必要な値は、秘密情報の語を含まないキーで書く。
  - **例外を投げない。** java.util.regex は選択肢を含むグループの繰り返しで 1 文字ごとに再帰するため、書き方によっては長い値でスタックがあふれる(`StackOverflowError`)。logback は `Error` を捕まえないので、ログの記録が業務の処理を失敗させる。引用符の値は展開したループと強欲な量指定子で書き、繰り返すグループには回数の上限を付ける。それでも失敗した場合は、元の文字列ではなく `[masking failed](N chars)` を返す。`MaskingSpec` で、スタックが 1MB のスレッド(Linux x64 の既定)で 16KB の値を伏せられることを検査する。
  - 値が閉じない引用符なら入力の末尾まで、括弧が対応しない(または 4,096 文字を超える)配列・オブジェクトなら行末まで伏せる。
  - **処理時間に上限を設ける。** マスキングはログを記録したスレッドで同期して走るため、長い入力で入力長の 2 乗の時間がかかると、外部から来た長い値だけでワーカーが止まる(DoS)。どの正規表現も、開始位置を後読みで語の先頭に限り、繰り返しに上限を付ける。入力は 64KB を上限に先頭と末尾を残して捨て(末尾は改行の次から始める)、**伏せてから** 16KB に切り詰める(先頭と末尾の 8KB ずつ。`…[truncated N chars]…`)。伏せてから切るので、切れ目でキーと値が分かれても値は残らない。末尾を残すのは、スタックトレースの根本原因(`Caused by:`)が末尾に書かれるため。`MaskingSpec` で 64KB 近い病的な入力が 2 秒以内に終わることを検査する。
  - 誤検知と見逃しは、秘密情報は伏せる側(誤検知を許す)に、個人情報の数字は見逃す側(誤検知を減らす)に倒す。キーのない `Bearer` / `Basic` は資格情報らしい値(8 文字以上で、数字・記号を含むか大文字と小文字が混ざる)だけを伏せる。電話番号は、携帯の 11 桁(`0[789]0`。区切りは `-`・空白・なし)と `-` で区切った固定電話だけを伏せる。0 始まりの 10 桁の ID と、空白区切りの日付(`09 27 2026`)は伏せない。
  - 必須キー(JSON)や、アペンダが設定する属性(OTLP)と同じ名前の MDC のキーは、`mdc.` を付けて出す。必須キーを上書きさせないため。
  - console 形式でも、メッセージの改行はエスケープして 1 イベント 1 行を保つ。
  - ID のキー(`trace_id` など)は形式を検証済みなので伏せない。数字だけの span_id がカード番号と誤判定されるのを避けるため。
  - プラグインはリクエストとレスポンスの本文を読まず、ログにも出さない。ログを書く側も、受け取った値ではなく参照キーを書く(Framework 14.1)。マスキングは最後の防御であり、網羅は保証しない。

### 4. alpha の計装ライブラリは使わず、必要な範囲を自前で実装する
版の確認日: 2026-09-27(Maven Central)。

| 成果物 | 版 | 状態 | 用途 | 方針 |
|---|---|---|---|---|
| `io.opentelemetry:opentelemetry-api` / `-sdk` / `-exporter-otlp` / `-exporter-sender-jdk` / `-sdk-testing` | 1.66.0 | 安定版 | SDK・OTLP の送信・テスト | 使う |
| `io.opentelemetry.semconv:opentelemetry-semconv` | 1.44.0 | 安定版 | 安定した属性名(HTTP・server・error・exception・service) | 使う。incubating(`-alpha`)は使わない |
| `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0` | 2.31.1-alpha | alpha | ログの OTLP 送信 | 使わない。`OtlpLogAppender` を自前で実装した(§3) |
| `io.opentelemetry.instrumentation:opentelemetry-ktor-3.0` | 2.31.1-alpha | alpha | Ktor の計装 | 使わない。`ServerObservability` / `ClientObservability` を自前で実装した |
| `io.opentelemetry.instrumentation:opentelemetry-kafka-clients-2.6` | 2.31.1-alpha | alpha | Kafka の計装(P06・P07) | 使わない。下の範囲を自前で実装する |
| `io.opentelemetry.instrumentation:opentelemetry-jdbc` | 2.31.1-alpha | alpha | JDBC の計装(P06) | 使わない。下の範囲を自前で実装する |

alpha は、版を上げるたびに API や属性名が変わりうる。全社の連携実装のテンプレートになる `platform/*` の本番コードでは、alpha の成果物に依存しない(`libs.versions.toml` の「alpha / beta / RC は使わない」と同じ方針)。

P06・P07 で自前で実装する範囲(`platform/messaging-kafka` / `platform/outbox`):
- **Kafka のヘッダの伝搬**: 登録済みの Propagator(`ObservabilityRuntime.openTelemetry.propagators`)を、Kafka の `Headers` を読み書きする `TextMapGetter` / `TextMapSetter` で使う。`traceparent` / `tracestate` はメッセージのヘッダに、Correlation ID は `correlationid` のヘッダに載せる(INTEGRATION_STANDARDS §2)。
- **Kafka の span**: 送信で PRODUCER、受信した 1 件の処理で CONSUMER の span を作る。受信した `traceparent` を親にする。バッチで受信した場合は、各メッセージを親にした処理の span を作る。messaging の属性は semconv の incubating にしかないため、使う属性名を `platform/messaging-kafka` の定数にまとめ、semconv が安定したら差し替える。
- **Outbox(Debezium)**: 業務のトランザクションの中で、現在の `traceparent` と Correlation ID を outbox の行に書き、Debezium の Outbox Event Router がヘッダに移す(形式は ADR-0007 に従い、P06 で決める)。
- **JDBC**: SQL 文ごとの span は作らない(件数が多く、SQL の文字列は機密を含みうる)。必要な場合はユースケースやトランザクションの単位で span を作る。
- 計装ライブラリに安定版が出たら、この表を見直す。見直すまでは `ObservabilityRuntime` と Propagator の API だけに依存させ、置き換えの範囲を `platform/*` に閉じる。

### 5. RED メトリクス
- OTel semconv の `http.server.request.duration` / `http.client.request.duration`(単位は秒のヒストグラム)で、件数(Rate)・`error.type` 付きの件数(Error)・分布(Duration)を表す(Framework 14.1)。
- 属性は、`http.request.method`(既知でないメソッドは `_OTHER`)、`http.route`(テンプレートのみ)、`http.response.status_code`、`error.type`、`integration_id`(サーバ)、`server.address`(クライアント)。生のパスや ID は入れない(カーディナリティと機密のため)。
- エラーの定義は semconv に従う。サーバは 5xx と例外、クライアントは 4xx 以上と例外。
- **キャンセルはエラーに数えない。ただしタイムアウトは数える。**
  - クライアントの切断や呼び出し側の中止で処理がキャンセルされた場合は、応答していないので `http.response.status_code` を記録せず、`error.type` も付けない。500 として数えると、実際には返していない 5xx が Error 率と SLO のアラートを押し上げるため。
  - タイムアウトは `error.type=timeout`(semconv が許す低カーディナリティの独自の値)を付けてエラーに数える。同期呼び出しの 4 点セット(Framework 13)の Timeout の失敗を、Error 率に出すため。対象は `withTimeout` の期限切れ(`TimeoutCancellationException`)、Ktor の `HttpRequestTimeoutException` / `ConnectTimeoutException`、読み取りのタイムアウト(`java.net.SocketTimeoutException`)。
  - サーバのハンドラの中でタイムアウトした場合、Ktor は 504 を返す(テストで確認)。ステータスに 504 を記録し、Correlation ID の付いた WARN を 1 回残す。
  - 判定は `HttpMetrics.failureErrorType` に集め、Server / Client のプラグインと `withSpan` が同じ規則を使う。

## Alternatives Considered
- **OTel の Java エージェント**: §1 の表のとおり。KMP SDK と伝搬の規則が二重になるため不採用。
- **OTel 標準の `W3CTraceContextPropagator` をそのまま使う**: JVM 側は楽になるが、SDK(`TraceParent`)と不正な入力の扱いが食い違う(§1 の表)。同じ入力で JVM と SDK の判断が変わるため不採用。
- **Correlation ID を Baggage で運ぶ / Baggage と `X-Correlation-Id` の両方で運ぶ**: §2 のとおり不採用。
- **Collector がコンテナのログを読む**: §3 の表のとおり不採用。
- **logstash-logback-encoder で JSON にする**: 9.0 は Jackson 3(tools.jackson)を引き込み、logback 1.5 系に対してビルドされている(ここでは logback 1.6 を使う)。必須キーが固定で、マスキングを 1 か所で必ず通したいため、kotlinx.serialization による小さなエンコーダ(`EiaLogEncoder`)にした。
- **OTLP の送信に OkHttp を使う(exporter の既定)**: OkHttp が引き込む Kotlin stdlib の版とビルドの Kotlin の版が衝突しうる。JDK の HttpClient による送信部(`opentelemetry-exporter-sender-jdk`)を使う。
- **初期化前のログを捨てる / 無制限に溜める**: 捨てると起動時の障害が Loki から追えない。無制限に溜めると、初期化に失敗したときにメモリを使い続ける。上限つきで溜める。

## Consequences(トレードオフ)
- Kafka・JDBC の計装を自前で持つため、P06・P07 の実装とテストが増える。代わりに、alpha の API の変更に振り回されず、SDK と同じ伝搬の規則を保てる。
- `EiaTraceContextPropagator` は OTel 標準と不正な入力の扱いが異なる。ほかのサービス(OTel 標準の実装)から見ると、OWS つきの値を EIAF だけが受け付けるなどの差が出る。差は §1 の表とテストで固定し、OTel の版を上げたら同じテストで確かめる。
- `GlobalOpenTelemetry` を使わないため、グローバルな OTel を前提にするライブラリは、そのままでは計装されない。
- マスキングは正規表現による最後の防御である。13〜19 桁で Luhn に通る数字の列(ミリ秒のエポック時刻など)を誤って伏せることがある。
- ログは Collector が止まっている間は OTLP 側で欠けうる(標準出力には残る)。
- 初期化前のログの上限(1,000 件)を超えた分は OTLP には送られない(標準出力には残り、捨てた件数は WARN で分かる)。
- ログの trace は、記録した時点の現在の span(フラグを含む)を優先し、なければ MDC の trace_id / span_id を使う。MDC だけから作った場合、trace flags は既定値(00)になる。
- **外部から受け取った sampled フラグを信じる。** サンプラは ParentBased のため、外部の呼び出し元が sampled=1 を送れば記録される。社外に公開する入口では、Gateway(APISIX)で外部の `traceparent` を捨てて付け直すか、信じるかを P05 で決める(ROADMAP P05)。
- **OTLP の送信は、ローカル参照実装では平文で、認証もない。** 本番の構成では TLS(可能なら mTLS)と送信先の認証が要る。ローカル基盤の転送路の暗号化と合わせて Issue #29 で扱う。資格情報は ③ security の `SecretProvider` から渡す。
