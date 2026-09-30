# ADR-0010: アーキテクチャテスト(Konsist)の検査方式と例外
- Status: Accepted
- Date: 2026-09-25
- Framework 参照章: 16, 19

## Context
P00 で `tools/architecture-test` に Konsist のアーキテクチャテストを実装した。実装の過程で、規約の文面だけでは検査方式を決められない点が 3 つあった。
- CLAUDE.md §4 は依存方向を「domain ← application ← adapters ← app のみ。逆方向・スキップ参照禁止」と定めている。一方で、同じ §4 は「DTO と domain の変換は adapters のマッパーで行う」としており、adapters から domain のコードを参照する必要がある。
- ADR-0004 §2 は commonMain で `kotlin.jvm.*` の import を禁止している。一方で CODING_STANDARDS は値オブジェクトを `@JvmInline value class` で表現するよう定めており、commonMain では `import kotlin.jvm.JvmInline` が必要になる。`JvmInline` は stdlib の common API で、JVM 専用 API ではない。
- `kotlin.Result` は暗黙に import されるため、import の有無だけでは使用を検出できない。detekt の `ForbiddenMethodCall` は型解決が必要で、`./gradlew build` が実行する `detekt` タスク(型解決なし)では `runCatching` を検出できない。

## Decision
1. **レイヤの判定**: パッケージ `io.eia.<service>.<layer>` でレイヤを判定する。あわせて、`services/<service>/<layer>/` 配下のファイルのパッケージが `io.eia.<service>.<layer>` で始まることを検査し、配置とパッケージの食い違いによるすり抜けを防ぐ。検査は services 配下のディレクトリを列挙してサービスごとに生成する。
2. **依存方向**: コード参照(import)は、外側のレイヤから内側の任意のレイヤへの参照を許可する(adapters → application / domain、app → adapters / application / domain)。内側から外側への参照は禁止する。「スキップ参照禁止」は Gradle のモジュール依存の宣言に適用する。各レイヤの `build.gradle.kts` は直下のレイヤだけを宣言し、それより内側のレイヤは `api` による推移的依存で参照する。
3. **commonMain の禁止 import の例外**: `kotlin.jvm.JvmInline` だけを許可する。`kotlin.jvm` 配下のそれ以外(`Synchronized` など)は引き続き禁止する。ほかの `kotlin.jvm.*` が必要になったときは、ADR で個別に追加する。
4. **`kotlin.Result` の検出**: 次のどれかに該当したら違反とする。
   - `import kotlin.Result` がある
   - `kotlin.Result` を完全修飾名で使っている
   - `Result<...>` を使っているのに、`Result` を import もエイリアスもせず、同一パッケージでも宣言していない(この場合 `kotlin.Result` に解決される)
   - `runCatching` を呼び出している

   コメントと文字列リテラルは検査の対象外とする。detekt(`ForbiddenImport`)も同時に使う。`ForbiddenMethodCall` は型解決付きのタスク(`detektJvmMain` など)でのみ有効なので、補助として扱う。
5. 違反サンプル(`src/test/resources/fixtures/violations`)でルールが失敗すること、準拠サンプル(`fixtures/compliant`)で成功することを、ルールごとにテストする。
6. **shared の許可リストと DomainError 分類の排他**(P01 で追加):
   - `shared/kernel` の commonMain は `kotlin.*` と自身(`io.eia.shared.kernel.*`)だけを、`shared/canonical-model` の commonMain はそれに加えて `kotlinx.serialization.*` と `io.eia.shared.canonical.*` だけを import できる。`shared/resilience` の commonMain は、`kotlin.*`・`kotlinx.coroutines.*`(ADR-0021 §10)・kernel・自身だけを import できる。禁止リスト(Decision 3・ADR-0004 §2)は既知のフレームワークしか検出できないため、基盤モジュールでは許可リストでフレームワーク依存ゼロを担保する。
   - `DomainError.Retryable` と `DomainError.NonRetryable` の両方を、間接的な継承も含めて実装する型を禁止する(ADR-0011 §6)。継承関係はコードベース内の型の単純名で辿る。

## Alternatives Considered
- Konsist の `assertArchitecture` を使う: レイヤにファイルが 1 つもないと失敗し、空モジュールの段階(P00)では使えない。サービス間依存と配置の検査も別途必要になる。import ベースの独自検査にした。
- 依存方向を隣接レイヤに限定する(adapters → domain を禁止する): マッパーが domain を参照できず、CLAUDE.md §4 と矛盾する。不採用。
- `kotlin.jvm.*` を全面的に禁止したまま、値オブジェクトに `@JvmInline` を使わない: JVM で boxing が発生し、CODING_STANDARDS と矛盾する。不採用。

## Consequences(トレードオフ)
- 完全修飾名で書いた参照(import なし)による依存方向違反は検出できない。Gradle のモジュール依存(Decision 2)で大半を防ぐ。
- `kotlin.Result` の検出はテキストに基づくため、稀に誤検知しうる。誤検知が出たら、`io.eia.shared.kernel.Result` を明示的に import すれば解消する。
- 禁止 import のリストはライブラリを追加するたびに更新が必要(ADR-0004 と同じ)。

## 改訂履歴
- 2026-09-25: Decision 6(shared の許可リスト、DomainError 分類の排他)を追加(P01)
- 2026-09-30: Decision 6 に `shared/resilience` の許可リスト(`kotlinx.coroutines` を追加)を明記した(P04b。ADR-0021 §10)
