# ADR-0043: GraphQL BFF(ライブラリ・契約・検査)
- Status: Accepted
- Date: 2026-11-03
- Framework 参照章: 3.1, 3.2, 3.3, 5.1, 12.3, 15
- 関連: ADR-0013(contract-check)、ADR-0042(社内の照会の gRPC)、ADR-0023(Gateway)、ADR-0024(リクエストの予算)
- 番号: P13 の計画で決めた番号(0042 gRPC / 0043 GraphQL BFF)
- 書く順: P13 ⑥ で §1〜§3(ライブラリと契約)を書く。実行時の決定(クエリの制限の数え方・部分応答・予算の配分・認可・線引き・Rate Limit)は P13 ⑦・⑧ で §4 以降に足す

## Context
P13(#16)で、画面のために注文・在庫・出荷を集約する `bff-graphql` を作る。Framework 3.1 は GraphQL を「BFF、フロント向けデータ集約」に限り、3.3 は「認可漏れ・無制限クエリの許容」をアンチパターンとして挙げる。

ここでは、ライブラリと、契約(SDL)の置き方と、破壊的変更の検査を決める。

## Decision
### 1. ライブラリ: graphql-java(SDL ファースト)
確認日 2026-11-03。

| 観点 | **graphql-java 26.1(採用)** | graphql-kotlin 11.0.0 | KGraphQL 0.42.0 |
|---|---|---|---|
| スキーマの作り方 | **SDL から作れる** | Kotlin のコードから作る(README の `generator` は Code-First) | Kotlin の DSL で作る |
| Contract First(CLAUDE.md §5) | SDL が契約で、実装はそれに合わせる。合わないと起動時に分かる | 契約がコードの後になる。SDL は生成物 | 同左 |
| 深さ・複雑度の制限 | `MaxQueryDepthInstrumentation`・`MaxQueryComplexityInstrumentation`、パーサのトークンの上限 | graphql-java の上に作られている(11.0.0 は graphql-java 26.1 に依存) | 設定の一覧(`configuration.md`)に見当たらない |
| N+1 | java-dataloader(graphql-java の依存) | java-dataloader を使う | 独自の `dataProperty` |
| イントロスペクションの無効化 | リクエストごとに無効にできる(`Introspection.INTROSPECTION_DISABLED`) | graphql-java と同じ | `introspection` の設定 |
| Ktor との結合 | 自分で書く(1 つのルートと JSON の変換だけ) | サーバのモジュールがある | Ktor のプラグインがある |
| Kotlin との相性 | Java の API で、suspend は `CompletableFuture` への橋渡しが要る | よい | よい |

- **採用の決め手は Contract First**。SDL を contracts に置き、そこから実行するスキーマを作るので、契約と実装が離れない。
- 不便な点(Java の API、suspend の橋渡し、ルートを自分で書く)は、BFF の中の小さな部品で閉じ込められる。
- graphql-java は `SchemaDiff` も持つので、破壊的変更の検査にも同じ版を使える(§3)。

### 2. 契約: `contracts/graphql/storefront.v1.graphqls`
- ファイル名は `{domain}.v{n}.graphqls`。連携は INT-STOREFRONT-001(`style: graphql`)。公開パスは `/storefront/v1/graphql`(ADR-0005)。GraphQL は版を持たないのが一般的だが、公開パスの規約(ADR-0005)に合わせる。破壊的変更は新しいファイル(v{n+1})で行う。
- **読み取りだけ**。`Mutation`・`Subscription` を置かない(`CC-GRAPHQL-001`)。
- **ディレクティブで、次のことを契約に書く。** どれも contract-check が検査し、BFF は起動時に SDL から読む(コードに値を複製しない)。
  | ディレクティブ | 内容 | 検査 |
  |---|---|---|
  | `@requiresScope(scope:)` | 項目を読むのに必要なスコープ。`Query` の項目には必須 | `CC-GRAPHQL-002` |
  | `@maxItems(value:, upstreamOperation:, upstreamParameter:)` | リストの引数の上限。下流の API の上限以下 | `CC-GRAPHQL-003` |
  | `@queryLimits(...)` | クエリの上限(Framework 5.6「制限値は API 契約に明記」) | `CC-GRAPHQL-003` |
- **`orders(ids:)` の上限は 20 件で、REST の `GET /v1/orders?ids=` の `maxItems` 以下であることを contract-check が確かめる**(P13 の承認時の条件 3)。REST の上限を下げると、SDL を直すまで `CC-GRAPHQL-003` で失敗する。出荷の gRPC の上限(20 件。ADR-0042 §4)は proto のコメントにしかなく、機械的には比べられない。proto を変えるときに合わせる。
- **「ない」と「読めなかった」を区別する**: 項目はエラーなしの `null` なら「ない」、エラーつきの `null` なら「読めなかった」(ADR-0042 §4 と同じ考え方)。
- **状態の `enum` には `UNKNOWN` を置く**。REST の契約は「値は追加されうる」としているので(Tolerant Reader)、BFF は一覧にない値を `UNKNOWN` にする。応答の型を変えずに済む。
- 届け先の住所(個人情報)は `sales.order:read-address` を別に要求する。

### 3. 破壊的変更の検査: `SchemaDiff` + 適用したディレクティブの比較(`CC-COMPAT-007`)
| 候補 | 評価 |
|---|---|
| **graphql-java の `SchemaDiff`(採用)** | JVM の中で動き、外部の CLI を増やさない。BFF と同じ版を使う |
| graphql-inspector | 規則は豊富だが Node.js が要る。contract-check を Kotlin と固定した CLI に保つ方針(ADR-0013)に合わない。不採用 |
| 自前で全部を比べる | 規則の網羅に工数がかかる。不採用 |

**検出力を確かめた**(`ContractCheckSpec`)。

| 変更 | `SchemaDiff` |
|---|---|
| 項目の削除、型の変更、null にならない項目を null にできるようにする、リストの要素を null にできるようにする | 破壊的(検出する) |
| enum の値の削除、必須の引数の追加、引数の削除、引数の型の変更 | 破壊的(検出する) |
| 項目の追加、任意の引数の追加、null にできる項目を null にならないようにする | 互換(通す) |
| enum の値の追加 | 危険の区分(`UNKNOWN` があるので、違反にしない) |
| **適用したディレクティブの変更(`@maxItems`・`@requiresScope`・`@queryLimits`)** | **見えない** |

- `SchemaDiff` はイントロスペクションの結果で比べるので、適用したディレクティブは比べられない。そこで contract-check が SDL を読んで比べる。**上限を下げる・スコープを変える(足す)のは、今の呼び出しを拒否することになるので、破壊的変更にする**。
- `SchemaDiff` の `@PublicSpi` は、変わりうる API という印。版を上げて動かなくなれば、`ContractCheckSpec` が失敗する。

## Alternatives Considered
- **コードからスキーマを作り、生成した SDL を contracts にコミットして比べる(graphql-kotlin)**: 比べることはできるが、契約が実装の後になる。CLAUDE.md §5 の Contract First に反する。不採用。
- **`status` を `String` にする**: 値の追加で型が変わらないが、画面が値の一覧を型で知れなくなる。`UNKNOWN` のある `enum` にする。
- **上限をコードの定数で持つ**: Framework 5.6 の「制限値は API 契約に明記」を満たせず、契約との二重管理になる。不採用。

## Consequences(トレードオフ)
- contract-check に graphql-java の依存が増える。
- スコープを足すことも破壊的変更になるので、公開した項目の認可を厳しくするときは、例外の手続き(ADR-0013 §6)が要る。
- 出荷の gRPC の上限と SDL の上限の一致は、機械的に確かめていない(§2)。

## 改訂履歴
