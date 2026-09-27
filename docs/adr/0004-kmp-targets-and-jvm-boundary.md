# ADR-0004: KMP ターゲット構成と JVM 専用ライブラリの境界
- Status: Accepted
- Date: 2026-09-25
- Framework 参照章: 1.9, 13, 14, 19

## Context
ADR-0001 で domain / application / shared を KMP(commonMain)とした。一方で次の問題がある。
- `macosArm64` は macOS ホストでしかコンパイル・テストできず、ubuntu の CI では検証できない。private リポジトリの macOS ランナーは消費分数の倍率が高い。
- Kotlin/Native のコンパイルは遅く、全サービスの domain / application を 4 ターゲットでビルドすると CI 時間が数倍になる。
- 単一ターゲット(jvm のみ)の KMP モジュールでは、commonMain から JVM API を使えないことをコンパイラが保証しない。
- Kafka clients / Exposed / Flyway / OTel SDK / Resilience4j / avro4k などは JVM 専用。一方で P11 の KMP SDK とデバイスシミュレータ(native)でも Retry・Circuit Breaker・traceparent 伝搬が必要になる。
- kernel が提供する `Result<T, E>` は、標準ライブラリの `kotlin.Result` と名前が衝突し、import ミスで混在しやすい。

## Decision
### 1. ターゲット構成
| モジュール | ターゲット | テスト実行 |
|---|---|---|
| `shared/*`(kernel, resilience, canonical-model, integration-sdk) | jvm, js(IR), linuxX64, macosArm64 | ubuntu CI: jvm / js / linuxX64。macosArm64 は `shared/**` 変更時のみ macOS ランナーの別ジョブ(またはローカル実行の証跡で代替) |
| `services/*/{domain,application}` | **jvm のみ**(ソースは commonMain) | ubuntu CI |
| `tools/device-simulator` | linuxX64, macosArm64(実行バイナリ) | P11 で定義 |
| `platform/*`, `services/*/{adapters,app}`, `tools/*`(上記以外), `tests/e2e` | JVM | ubuntu CI |

- build-logic の `eia.kmp-library` は、ターゲット集合を拡張関数(例: `eiaTargets { jvm(); js(); native() }`)で宣言できる形にする。
- services の domain / application は `eia.kmp-domain` を使い、既定は `jvm()` のみとする。将来 js / native を追加するときは、同じ DSL にターゲットを 1 行追加するだけで済むようにしておく(P00 で実装)。

### 2. commonMain の純粋性(Konsist で強制)
`services/*/{domain,application}` と `shared/*` の commonMain では、次の import を禁止する:
`java.*`, `javax.*`, `kotlin.jvm.*`, `io.ktor.*`, `org.apache.kafka.*`, `org.jetbrains.exposed.*`, `org.koin.*`, `org.flywaydb.*`, `io.opentelemetry.*`, `com.github.avrokotlin.*`, `io.github.resilience4j.*`。
(`shared/integration-sdk` の `io.ktor.client.*` だけは例外として許可する)

### 3. `kotlin.Result` の使用禁止
- 全モジュールで `kotlin.Result` と `runCatching` の戻り値を業務の制御フローに使うことを禁止し、`io.eia.shared.kernel.Result` に統一する(パッケージ規約は MODULE_DESIGN §1)。
- Konsist で「`kotlin.Result` を import または戻り値型に使う宣言」を検出したらテストを失敗させる。detekt のカスタムルール `ForbiddenImport` / `ForbiddenMethodCall` でも併せて検出する。
- 境界で例外を捕捉するヘルパーは `io.eia.shared.kernel.catching { }` として kernel 側で提供する。

### 4. JVM 専用ライブラリの配置
| ライブラリ | 使ってよい層 |
|---|---|
| Kafka clients, Debezium, Apicurio serde, avro4k | `platform/messaging-kafka`, `platform/outbox`, `services/*/adapters` |
| Exposed, Flyway, JDBC | `platform/outbox`, `services/*/adapters` |
| OTel SDK | `platform/observability`, `services/*/app`(Konsist で検査。テストのソースセットは除く) |
| Ktor Server, Koin | `platform/*`, `services/*/{adapters,app}` |
| MockK, Testcontainers | 上記モジュールの test / integrationTest |
| Konsist | `tools/architecture-test` |

### 5. 回復性の中核を KMP に置く
- `shared/resilience`(KMP、新設)に置くもの: Retry(`kernel.RetryPolicy` を利用)、Timeout、Circuit Breaker の状態機械、Bulkhead(Semaphore)、Fallback 合成。すべて coroutines ベースで作り、`Clock` と `Random` をインジェクションする。W3C traceparent の生成・解析もここに置く(`io.eia.shared.resilience.trace`)。Correlation ID は `shared/kernel` の `CorrelationId` を使う(resilience は kernel に依存するため、SDK からも両方を使える。改訂履歴 2026-09-26)。
- `platform/reliability` と `platform/observability` は、これを Ktor / Kafka / OTel に結線する JVM アダプタとする。
- traceparent の trace-flags は、W3C Trace Context Level 2(Candidate Recommendation Draft 2024-03-28)に従って扱う。
  - 版 00 の受信では sampled(0x01。§3.2.2.5.1)と random(0x02。§3.2.2.5.2)だけを残す。それ以外のビットは §3.2.2.5.3 Other Flags の「Vendors MUST set those to zero」に従って 0 にする。
  - 未知の上位版の受信でも、sampled と random を残す。
    - 根拠 1: §4.1.2 A traceparent is Received は、上位版から「この版の仕様が扱うフラグ」だけを読み、「unparsed / unknown trace-flags」を送信時に 0 にするとしている。Level 2 が扱うフラグには random も含まれる。
    - 根拠 2: §3.2.2.5.2 は、受信した random フラグを同じ trace-id の送信で立てたままにすることを MUST としている。
    - §3.2.4 Versioning of traceparent の上位版の解析手順(SHOULD)は「sampled bit」だけを挙げる。しかしこれは Level 1 の文言が残ったもので、根拠 2 の MUST と両立しない。§3.2.4 は将来の版を追加的(additive)とするため、上位版でも 0x02 の意味は変わらないとみなす。
  - 送信時(`format()`)も、定義済みのビット以外を 0 にする。直接組み立てた値にも効かせるため。
  - 生成(`TraceParent.generate()`)では random フラグを立てる。§3.2.2.5.2 は、trace-id の右 7 バイトをランダムに生成したときにこのフラグを立てるべき(SHOULD)としており、`TraceId.generate()` は 16 バイトすべてを乱数で作るため。
  - 勧告(Recommendation)は Level 1(2021-11-23)だが、random フラグは Level 2 で定義されたものである。Level 1 しか知らない受信側にとって 0x02 は未定義のビットになり、0 にされるだけなので、Level 2 に合わせても互換性は損なわない。

## Alternatives Considered
- 全モジュールを 4 ターゲットにする: CI コストが過大で、macOS ランナーが常に必要になる。不採用。
- services も jvm + js の 2 ターゲットにしてコンパイラで純粋性を担保する: ビルド時間が倍になる割に、Konsist で十分に検出できる。不採用(将来の選択肢として DSL で残す)。
- Resilience4j に一本化する: native / js の SDK で使えない。不採用。
- Arrow(arrow-resilience)を使う: KMP 対応だが `Either` と kernel の `Result` が併存し、学習コストが増える。不採用。

## Consequences(トレードオフ)
- サービス層の native / js での再利用は、ターゲットを追加し、そのターゲットで一度ビルドを通すまで保証されない。
- モジュールが 1 つ増える(`shared/resilience`)。CLAUDE.md と MODULE_DESIGN.md に反映する。
- Konsist の禁止リストはライブラリを追加するたびに更新が必要になる(PR テンプレートのチェック項目で担保する)。
- avro4k が JVM 専用なので、Canonical Model(commonMain)と Avro スキーマ(contracts)は別々の資産になる。P02 で、両者の一致を JVM テストで検査する。

## 改訂履歴
- 2026-09-25: パッケージ名を ADR-0010 / MODULE_DESIGN の規約に合わせて修正(決定内容の変更なし)
- 2026-09-26: §5 の「traceparent と Correlation ID の生成・解析」のうち、Correlation ID は P01 で `shared/kernel`(`CorrelationId`)に実装済みのため、kernel に置いたままとする。`shared/resilience` には traceparent(`io.eia.shared.resilience.trace`)を置く(P04a)。resilience は kernel に依存するため、SDK からも両方を使える。
- 2026-09-27: §5 に、`TraceParent.generate()` が random フラグを立てることを追記した(P04a ②。PR #30 のレビューの積み残し)。
- 2026-09-27: §4 の OTel SDK の配置を Konsist(`otelSdkOnlyInAllowedModules`)で検査するようにした(P04a ②。計装方式は ADR-0018)。
