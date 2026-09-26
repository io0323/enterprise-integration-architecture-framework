# ADR-0013: 契約検査(contract-check)の方式
- Status: Accepted
- Date: 2026-09-26
- Framework 参照章: 5.3, 6.2, 6.3, 12, 15.3, 16.1, 19

## Context
P02 で、`contracts/` の契約を CI で機械的に検査する仕組みを作る。決める必要があるのは次の点。
- 互換性の比較元: Avro の互換性と OpenAPI の破壊的変更は「main の版との差分」で判定する。Schema Registry は P03 まで使えないので、オフラインで比較する必要がある。
- OpenAPI の破壊的変更の検知に使うツール: 契約は OpenAPI 3.1 で書く(ROADMAP P02)。Java ライブラリの openapi-diff と、Go 製 CLI の oasdiff が候補になる。
- 構造の検証: AsyncAPI と OpenAPI の文書が仕様に適合しているかを、何を基準に判定するか。
- カタログの項目: コマンドトピックの購読者が 1 つだけであることを検査するには(ADR-0006)、どのサービスのどの Consumer Group がどのトピックを購読するかが要る。INTEGRATION_STANDARDS §5 のカタログにはその項目がない。
- 違反の伝え方: 修正する人が、根拠(Framework の章)と直し方を同じ場所で分かるようにしたい。

## Decision
### 1. 構成
- `tools/contract-check`(JVM の CLI。`io.eia.tools.contract`)に検査を集める。`./gradlew :tools:contract-check:run` で実行し、違反が 1 件でもあれば終了コード 1 を返す。
- ルール ID は `CC-{区分}-{3 桁}`(例 `CC-NAMING-001`)とし、一覧を `tools/contract-check/README.md` に載せる。README と定義(`Rule.kt`)が一致することはテストで検査する。
- 各違反は **ファイル / ルール ID / 内容 / Framework 章 / 修正方法** の 5 項目で出力する。GitHub Actions の Step Summary にも同じ 5 列の表を出す。

### 2. 比較元(baseline)
- 比較元は `git archive <baseRef> contracts` で `build/contracts-baseline/` に取り出す。`baseRef` の既定は `origin/main` で、`-Peia.contracts.baseRef` で変えられる。CI の pull_request では PR のマージ先、main への push では push 前のコミットを使う。
- 比較は同じ相対パスのファイルどうしで行う。比較元にないファイルは新規とみなし、互換性は検査しない。
- 比較元を取り出せないときは、互換性検査(`CC-COMPAT-*`)を省略し、省略したことを出力に明記する。比較元があるのに oasdiff が使えない場合は `CC-TOOL-001` で失敗させ、黙って省略しない。
- Avro は Avro 付属の `SchemaCompatibility` で、main の版に対する FULL 互換(BACKWARD と FORWARD の両方向)を検査する(ADR-0014)。

### 3. OpenAPI の破壊的変更: oasdiff を採用する(スパイクの結果)
同じ OpenAPI 3.1 の契約(`order-api.v1.yaml`)に 5 種類の破壊的変更を加え、両方のツールで検出できるかを比べた(2026-09-26)。

| 観点 | openapi-diff 2.1.7(Java) | **oasdiff 1.32.1(Go CLI。採用)** |
|---|---|---|
| 必須パラメータの追加 | 検出 | 検出(`new-required-request-parameter`) |
| パスの削除 | 検出 | 検出(`api-path-removed-without-deprecation`) |
| 応答の必須項目の削除 | 検出 | 検出(`response-required-property-removed`) |
| リクエストの項目の型変更(integer → string) | **見逃し**(exit 0) | 検出(`request-property-type-changed`) |
| 3.1 の型配列による nullable の削除(`[string, "null"]` → `string`) | **見逃し**(exit 0) | 検出(`request-property-became-not-nullable`) |
| OpenAPI 3.1 への対応 | 部分的。3.1 の型の変更を扱えないことが open issue になっている(「[OpenAPI 3.1.0 support] JsonSchema type changes not handled」など) | 対応 |
| 保守状況 | 最新リリース 2026-01-26、最終コミット 2026-08-05 | 最新リリース 2026-09-15、最終コミット 2026-09-16 |
| CI への組み込みやすさ | Maven Central から依存として取得でき、プロセス内で動く | 単一の実行ファイル。取得と検証が必要。`--format json` で変更ごとにルール ID と level を出力し、解釈しやすい |
| ライセンス | Apache-2.0 | Apache-2.0 |

- 検出力(3.1 の型の変更)を優先して oasdiff を採用する。`oasdiff breaking <base> <revision> --format json` を外部プロセスで実行し、level が ERR の変更を違反(`CC-COMPAT-002`)にする。
- 取得は Gradle のタスクで行う。版は `libs.versions.toml` の `oasdiff`、SHA-256 は `tools/contract-check/build.gradle.kts` に固定し、一致しなければビルドを失敗させる。対応するプラットフォームは darwin(universal)、linux amd64 / arm64。

### 4. 構造の検証
- AsyncAPI 3.0 は、AsyncAPI Initiative の公式 JSON Schema(`asyncapi/spec-json-schemas` v6.11.1 の `3.0.0-without-$id.json`、Apache-2.0)をリポジトリに同梱して検証する(`CC-STRUCT-003`)。出典・版・SHA-256・ライセンスは `tools/contract-check/src/main/resources/asyncapi/README.md` と同じ場所の `LICENSE` に記録する。
- OpenAPI は swagger-parser の検証メッセージを違反として扱う(`CC-STRUCT-002`)。
- JSON Schema(manifest・カタログ)は networknt json-schema-validator で検証する。スキーマ自体もメタスキーマ(2020-12)で検証する(`CC-STRUCT-005`)。
- networknt 3.x は Jackson 3(`tools.jackson`)を、swagger-parser は Jackson 2(`com.fasterxml.jackson`)を使う。パッケージが別なので共存できる。contract-check 自身の YAML / JSON の読み込みは Jackson 3 に揃える。

### 5. カタログの拡張
INTEGRATION_STANDARDS §5 のカタログに次の項目を追加し、`contracts/catalog/catalog.schema.json` で形式を検査する。
- `channels`: 連携が扱うトピックなど。event / cdc では必須。AsyncAPI の `channels[*].address` と過不足なく一致させる(`CC-CATALOG-004`)。
- `consumers[*].group`: Kafka の Consumer Group(`{service}.{purpose}`)。event / cdc の consumer では必須(`CC-CATALOG-005`)。
- `senders`: `pattern: queue`(コマンド)を送信する側。
- `pattern: queue` の連携は、`provider` をコマンドを受信して処理するサービスとし、`consumers` はその受信サービスの 1 件(`group: {service}.command`)だけにする。同じコマンドトピックをほかのカタログから購読することも含めて、購読者が 1 件でなければ失敗させる(`CC-COMMAND-001`)。

## Alternatives Considered
- **比較元として Schema Registry(Apicurio)を使う**: 登録済みの版と比べられるが、CI でレジストリを起動する必要があり、P03 より前には使えない。main のファイルを比較元にすれば、レジストリなしで同じ判定ができる。不採用(P06 以降、レジストリ側の互換モードでも二重に守る)。
- **openapi-diff を使う**: 依存の取得は簡単だが、上の表のとおり 3.1 の型の変更を見逃す。不採用。
- **自前で破壊的変更の規則を実装する**: 規則の網羅(パラメータ・リクエスト・レスポンス・列挙値・制約)に工数がかかり、oasdiff に劣る。不採用。
- **oasdiff を Docker イメージで実行する**: 取得の手間は減るが、`./gradlew build` のテストで Docker が必須になる。不採用。
- **AsyncAPI を @asyncapi/parser(Node.js)で検証する**: 公式のパーサだが、JVM のビルドに Node.js の実行環境が加わる。JSON Schema で構造は十分に検証できる。不採用。
- **購読関係を AsyncAPI の `operations`(receive)で表す**: 契約は提供側が持つため、消費者の追加のたびに提供側の契約を変更することになる。カタログは連携ごとの台帳で、消費者の登録の置き場所として適切。不採用。

## Consequences(トレードオフ)
- 初回のビルドで oasdiff を GitHub Releases から取得するため、ネットワークが必要になる(2 回目以降は `build/` のものを使う)。版を上げるときは、チェックサムの表も更新する。
- 比較は同じ相対パスのファイルどうしで行う。ファイルの削除・改名は互換性検査の対象外になる。参照が切れた場合は `CC-EVENT-002` / `CC-CATALOG-002` で検出されるが、参照されていない契約を削除した場合は検出しない(ライフサイクルに沿った削除の検査は #22 で追加する)。
- Avro の `SchemaCompatibility` は論理型の変更(`timestamp-micros` → `timestamp-millis`)を非互換としない。Canonical Model に対応づけた型は `CC-CANON-001` で検出できるが、それ以外の型では検出できない。
- JSON Schema の検証メッセージは、実行環境のロケールに依存しないよう日本語に固定する。
- **解決済み**: Framework 6.3 の「BACKWARD 互換必須(消費者を先に更新せず発行者を進化可能)」は、括弧内が FORWARD 互換の性質を指していた。ADR-0014 で FULL 互換を要件とし、`CC-COMPAT-001` を FULL の検査にした。Framework 6.3・15.3 と INTEGRATION_STANDARDS §4 も ADR-0014 に合わせて直した。
