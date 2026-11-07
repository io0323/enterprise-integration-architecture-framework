# ADR-0043: GraphQL BFF(ライブラリ・契約・制限・部分応答・認可・線引き)
- Status: Accepted
- Date: 2026-11-03
- Framework 参照章: 3.1, 3.2, 3.3, 5.1, 12.3, 15
- 関連: ADR-0013(contract-check)、ADR-0042(社内の照会の gRPC)、ADR-0023(Gateway)、ADR-0024(リクエストの予算)
- 番号: P13 の計画で決めた番号(0042 gRPC / 0043 GraphQL BFF)
- 書く順: P13 ⑥ で §1〜§3、P13 ⑦a で §4〜§8、P13 ⑧a と ⑧b で §9 を書く

## Context
P13(#16)で、画面のために注文・在庫・出荷を集約する `bff-graphql` を作る。Framework 3.1 は GraphQL を「BFF、フロント向けデータ集約」に限り、3.3 は「認可漏れ・無制限クエリの許容」をアンチパターンとして挙げる。

ここでは、ライブラリと、契約(SDL)の置き方と、破壊的変更の検査(P13 ⑥)と、実行時の決定(P13 ⑦)を決める。

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

### 4. 過大なクエリの拒否(ROADMAP P13 の DoD)
上限は SDL の `@queryLimits` と `@maxItems` から読む。

| 上限 | 値 | 段階 |
|---|---|---|
| 本文の大きさ(`maxBodyBytes`) | 16 KiB | ルート。`Content-Length` を信じず、読みながら確かめる |
| トークンの数(`maxTokens`) | 2000 | パーサ |
| リストの引数(`@maxItems`) | `orders(ids:)` 20 件。**クエリ全体の注文の ID(重複を除く)も 20 件まで** | 実行の前 |
| 別名の数(`maxAliases`) | 10 | 実行の前 |
| 深さ(`maxDepth`) | 8 | 実行の前 |
| 複雑度(`maxComplexity`) | 200 | 実行の前 |
| 展開した項目の数 | 2000(コードの定数) | 実行の前。超えたら `maxComplexity` として拒否 |

- **拒否は 400 + `errors[].extensions`(`code: QUERY_LIMIT_EXCEEDED`、`limit`)で、下流を 1 回も呼ばない。** データの取得の前に動く Instrumentation で確かめる。テストは偽の下流の呼び出しの数で確かめる。
- **フラグメントをすべて展開し、変数を当てはめた後のクエリで数える**(graphql-java の `ExecutableNormalizedOperation`)。
  - `QueryTraverser` は**同じフラグメントを 1 回しか辿らない**。⑦a のテストで、再利用したフラグメントで別名の上限をすり抜けられることを確かめて、使うのをやめた。
  - 組み込みの `MaxQueryComplexityInstrumentation` も同じ弱点を持つことを実測した(既定の数え方。graphql-java 26.1)。同じ内容の項目を読むクエリで、インラインで 4 回書くと 16、同じフラグメントを 4 回使うと 7 になる。`MaxQueryDepthInstrumentation` も `QueryTraverser` を使う(ソースで確認。深さの過小評価は実測していない)。
  - 入れ子のフラグメントで項目を指数的に増やすクエリは、展開の途中で止める(`maxFieldsCount`)。
- **変数で渡した `ids` も同じ**(実行の前に当てはめた値で数える)。
- **クエリ全体の注文の ID も 20 件まで**(P13 ⑧a で追加)。DataLoader は別名で並べた `order`・`orders` を 1 回の呼び出しにまとめるので、引数ごとの上限だけでは、まとめた後に下流の上限(注文の REST・出荷の gRPC とも 20 件)を超える。`orders` 20 件を 2 つ並べるクエリは複雑度の上限にも当たらず通っていて、REST の 422 で失敗していた。分けて読むと §9 の枠の計算(リクエスト 1 件につき呼び出し 1 回)が崩れるので、分けずに拒否する。
- **複雑度の数え方**: 下流を呼ぶ項目と、その件数で数える。スカラーと、データを持っているだけの型(`Money`・`Address`)は数えない。
  | 項目 | 複雑度 |
  |---|---|
  | `Query.order` | 1 + 子 |
  | `Query.orders(ids:)` | 件数 × (1 + 子) |
  | `Order.lines` | 5 × 子(実行の前には明細の数が分からないので、1 注文 5 明細と仮定する) |
  | `Order.shipment`・`OrderLine.stock` | 1 + 子 |
  | そのほか | 子 |
  - 正当な最大のクエリ(`orders` 20 件で、明細の在庫と出荷を読む)は 20 × (1 + 5 + 1) = 140 で通る。別名で 2 回並べると 280 で拒否する。
- **受け付ける形**: POST だけ。1 つのクエリだけで、複数の操作をまとめた配列は受け付けない。
- **イントロスペクションは既定で無効**(リクエストごとに `INTROSPECTION_DISABLED`)。設定で有効にするのはローカルだけ。graphql-java の検証のエラーは項目の候補を出さない(テストで確かめた)ので、エラーの文からスキーマを推測されにくい。ただしスキーマは contracts にあり、秘密ではない。

### 5. N+1 にしない
- リクエストごとの DataLoader(`orders`・`inventory`・`shipping`)で、同じレベルの読み取りを 1 回にまとめ、重複を除く。別名で並べた `order` もまとまる。
- 下流の API はまとめて読むものだけにした(注文 `GET /v1/orders?ids=`、在庫と出荷の gRPC。ADR-0042 §4)。
- 在庫の上限(1 回 500 SKU)を超えるときは、ユースケースが分けて読む。明細の数に契約の上限がないため。
- **注文の呼び出しは、GraphQL のリクエスト 1 件につき 1 回**(注文を読むのはルートのレベルだけで、ID の合計は §4 で 20 件まで)。例外はリトライで、1 回の失敗につき呼び出しが増える。

### 6. 一部のサービスが遅い・止まっているとき
**取れた部分を返す**。これが同期呼び出しの 4 点セットの Fallback(CLAUDE.md §5)で、古いキャッシュは返さない。

| 読めなかった先 | 応答 |
|---|---|
| 注文 | 起点なので `order: null` / `orders: null` + errors。在庫と出荷は呼ばない |
| 在庫 | その項目だけ `stock: null` + errors(`path` はその項目)。ほかは返す |
| 出荷 | その項目だけ `shipment: null` + errors。ほかは返す |

- **「ない」と「読めなかった」を区別する**: 「ない」はエラーなしの `null`、「読めなかった」はエラーつきの `null`。画面が、読めなかった在庫を「在庫なし」、読めなかった出荷を「未出荷」と表示しないため。
- `errors[].extensions` は `code`(`UPSTREAM_UNAVAILABLE`・`UPSTREAM_ERROR`)、`service`、`retryable`。文は決まった文だけで、下流の情報と例外の文を出さない。
- 実行できたら、項目のエラーがあっても HTTP 200。**RED の Errors には出ない**ので、`eia.graphql.partial_responses{service}` で別に数える(1 つの応答でサービスごとに 1 回)。アラートは `BffPartialResponses`(15 分続いたら警告)。
- 1 つの下流の失敗で、ほかの読み取りを取り消さない(`supervisorScope`)。

**リクエストの予算の配り方**(ADR-0024 §3 の予算)
| 段階 | 上限 | 既定 |
|---|---|---|
| 全体 | `total` | 5 秒。Gateway の上流のタイムアウト 15 秒より短い(ADR-0024 §3) |
| 注文(先に読む) | `orderSlice` と、残り時間 − `reserve` の短い方 | 2 秒 |
| 在庫・出荷(並列) | 残り時間 − `reserve` | — |
| 応答の組み立ての余裕 | `reserve` | 0.3 秒 |

- 下流の打ち切りを全体の予算切れより先にするので、遅いサービスがあっても 503 にせず部分応答を返せる。残り時間がなければ下流を呼ばない。
- **起動の直後の最初のリクエストは、部分応答になることがある。** 下流の最初の呼び出しが、接続とクラスの読み込みで試行のタイムアウト(1.5 秒)に当たるため(CI と高負荷のローカルで観測した)。起動時に gRPC の接続を始めるが、トークンの取得とクラスの読み込みの分は残る。`/health/ready` は下流の状態を含めないので、この間もトラフィックを受ける。**接続を始めることでどこまで減るかは測っていない。**
- 読み取りごとに `withCallDeadline` で締め切りを渡し(`Resilience` と gRPC の deadline に届く)、下流の実装によらず `withTimeoutOrNull` でも打ち切る。

### 7. 認可と個人情報
- 項目ごとに SDL の `@requiresScope` で宣言し、呼び出し元のトークンのスコープで確かめる。**足りなければ、その項目だけ `null` + `FORBIDDEN`(`extensions.scope`)で、下流を呼ばない。** ほかの項目は返す。
- 起動時に確かめる: `Query` のすべての項目にスコープがある、スコープのある項目は null にできる型である、`@queryLimits` がある。足りなければ起動しない。宣言の漏れで認可なしのまま動かさないため(contract-check と二重)。
- **住所**(`Order.shippingAddress`)は `sales.order:read-address` を別に要求する。BFF は REST から住所を常に受け取るが、キャッシュせず、ログにも出さない。読んだことは、値を残さずに `eia.graphql.sensitive_field_reads{field, client}` と 1 リクエスト 1 行の構造化ログに残す。ハッシュチェーンの監査の対象にはしない(order も読み取りは対象外。ADR-0017)。

**confused deputy の緩和**
BFF は下流を自分の Client Credentials(`eiaf-bff-graphql`)で呼ぶ。利用者の権限は BFF が確かめるだけなので、BFF が読める範囲は、BFF の権限でならすべて読める(confused deputy)。

- **緩和策: `eiaf-bff-graphql` には読み取りのスコープだけを与え、書き込みのスコープ(`sales.order:write` など)を持たせない。** BFF が乗っ取られても、BFF のトークンでは更新できない。BFF のトークンで注文の作成が拒否されることを P13 ⑧ の `make verify` で確かめる。
- BFF を通るクエリは `Query`(読み取り)だけで、`Mutation` を置かない(`CC-GRAPHQL-001`)。
- **残るリスク**: BFF の認可の誤りは、下流では防げない。利用者ごとの認可(自分の注文だけ)は、このリポジトリにはない。本番でエンドユーザーの経路を作るときは、Token Exchange(RFC 8693)か所有者の確認が必須。

### 8. BFF の線引き
BFF は「画面のための集約」に限る(Framework 3 章)。

| 許可 | 禁止 |
|---|---|
| 下流の呼び出し、結合、形の変換、項目の選択、スコープによる出し分け、一覧にない状態を `UNKNOWN` にする | 状態遷移の判定、金額の計算、引当の可否の判定、DB、Kafka、Mutation |

- `available`(引当できる数)は inventory が計算した値を、金額は order が返した文字列を、そのまま返す。
- **`domain` のモジュールを置かない**(CLAUDE.md §3 の 4 モジュール構成からの逸脱)。置くと業務の規則の置き場所ができてしまう。`application` はビュー、Port、薄いユースケース(下流の上限ごとの分割)だけを持つ。
- 強制する。
  | 内容 | 仕組み |
  |---|---|
  | DB と Kafka を使わない | Konsist の `bffHasNoPersistenceOrMessaging` |
  | `Mutation` を置かない | `CC-GRAPHQL-001` |
  | graphql-java は BFF の adapters と contract-check だけ | Konsist の `graphqlOnlyInAllowedModules`・`LibraryDeclarationRules` |
- 金額や状態の計算を機械的に禁じることはできない。レビューで見る。

### 9. Rate Limit(2 段)
Gateway の枠はリクエストの数しか見ないので、重いクエリを続けるクライアントを止められない。BFF で複雑度も制限する。

#### Gateway のルート
- 公開パス `/storefront/v1/*` → `bff-graphql` の `/v1/*`(ADR-0005)。`aud` に `bff-graphql` があり、`azp` がある JWT だけを通す。スコープは BFF が項目ごとに確かめる(§7)。
- 上流のタイムアウトは 15 秒で、リクエストの予算(5 秒)より長い。Gateway では再送しない。
- BFF は Gateway の証明書(SAN `apisix`)だけを受け入れる(mTLS)。

- **トレースは BFF → Gateway で分かれる。** Gateway は受信した `traceparent` を捨てる(ADR-0023 §4)ので、BFF が Gateway 経由で order を呼ぶと新しいトレースになる。Gateway → BFF → 在庫・出荷が 1 つ、BFF → Gateway → order がもう 1 つで、両方を `X-Correlation-Id` でたどる(`BffE2E` で確かめる)。社内のクライアントの `traceparent` だけを通すには Gateway での判別が要るので、ここでは変えない。

#### Gateway のクライアントごとの枠
BFF は 1 つのクライアントとして `/sales/v1/orders` を呼ぶ。全クライアント共通の 60 件 / 60 秒のままだと、画面の全利用者の合計が約 1 件 / 秒に制限されるので、枠を分ける。

| クライアント | 枠 | 設定 |
|---|---|---|
| `eiaf-bff-graphql` | 600 件 / 60 秒 | `EIAF_RATE_LIMIT_CLIENTS`(`クライアント=件数` のカンマ区切り) |
| 一覧にないクライアント | 60 件 / 60 秒 | `EIAF_RATE_LIMIT_DEFAULT` |

- **`limit-count` の `count` で式は使えなかった。** 計画の時点では、文書の理解から `count` に式(lua-resty-expr)を書けると見ていた。APISIX 3.18.0 のソースを読むと、文字列の `count` は `$変数` を解決するだけで、実機でも式の文字列はすべての要求が 500 になった。
- そこで `count: "$eiaf_rate_limit"` とし、`azp` を取り出す既存の独自のコード(ADR-0023 §5 のコード 2)で変数に入れる。プラグインもルートも増えない。実機で、クライアントごとに別の枠で数えられ、偽の `X-Eiaf-Client-Id`・`X-Userinfo`・`eiaf_quota` ヘッダでは枠を変えられないことを確かめた。
- **枠の値はコードに書かない。** Lua のコードは「`azp` から枠を引く」仕組みだけで、値は環境変数から読む。
- **`count` の変数が空になる経路をなくす。** 変数が空だと `limit-count` は要求を 500 にする。一覧にない `azp` には必ず既定の枠を入れる。既定の枠が正の整数でない、一覧の形式が誤っているときは、apisix の起動の前に止める(compose の entrypoint)。`azp` がない要求は、変数を使う前に 401 になる。`make verify` で確かめる。

**600 件 / 60 秒の根拠**
DataLoader で、注文の取得は GraphQL のリクエスト 1 件につき order への呼び出し 1 回になる(§5)。そのため、

`BFF の枠 ≒ 画面のクライアントの数 × 画面のクライアントの枠`

600 は、ローカルでは画面のクライアント 10 個分(10 × 60)にあたる。**本番ではこの計算で決める。**

計算が外れる場合:
- リトライがあると、1 件のリクエストで呼び出しが増える。下流の障害のときに使用率が上がる。
- 注文を読まないクエリは呼び出しにならない。
- 枠で数えるのは Gateway に届いた呼び出しで、BFF が複雑度や上限で拒否したクエリは数えない。

#### 枠の使用率のアラート
`BffOrderQuotaHigh`: 1 分の呼び出しの数 / 枠が 80% を超えた状態が 5 分続いたら警告する。枠を超えると全クライアントの注文の項目が読めなくなるので、超える前に知らせる。分母の枠は Gateway の応答の `X-RateLimit-Limit` から読むので、値を二重に持たない。時間窓の 60 秒だけは、ルールの式に固定で書いている。

#### 複雑度のトークンバケット(BFF)
- キーは検証したトークンの `azp`。偽のヘッダで他人の枠を消費できない。
- §4 の複雑度を、上限以内のクエリだけ実行の前に消費する。足りなければ下流を呼ばず、429 + `Retry-After` + Problem Details `rate-limited` を返す。メトリクスは `eia.graphql.rejected_queries{limit="complexityRate"}`。
- 初期値は大きさ 1000、毎秒 60 戻す(`BFF_COMPLEXITY_BUCKET_CAPACITY`・`BFF_COMPLEXITY_REFILL_PER_SECOND`)。大きさは上限のクエリ(200)5 回分で、毎秒 60 は Gateway の既定の枠(60 件 / 60 秒)で平均の複雑度 60 まで続けられる値。**どちらも測定の根拠はない初期値**で、実際のクエリの分布を見て決め直す。
- 大きさが 1 回の上限より小さいと上限以内のクエリが永久に通らないので、起動の時点で誤りにする。
- **縮退**: バケットはプロセスの中に持つ。本番で複数台にすると、実質の枠は台数倍になる。台数で割るか、共有の保存先が要る。

## Alternatives Considered
- **コードからスキーマを作り、生成した SDL を contracts にコミットして比べる(graphql-kotlin)**: 比べることはできるが、契約が実装の後になる。CLAUDE.md §5 の Contract First に反する。不採用。
- **`status` を `String` にする**: 値の追加で型が変わらないが、画面が値の一覧を型で知れなくなる。`UNKNOWN` のある `enum` にする。
- **`MaxQueryDepthInstrumentation`・`MaxQueryComplexityInstrumentation` をそのまま使う**: §4 の実測のとおり、再利用したフラグメントの複雑度を過小に数える。別名の上限も持たない。不採用。
- **一部のサービスが止まったら全体を失敗にする**: 実装は簡単だが、在庫のサービスの障害で注文の画面まで見られなくなる。Tier の低い依存先が全体を止める。不採用。
- **読めなかった項目を空の値(在庫 0 など)で返す**: 画面が読めなかったことを区別できず、誤った表示になる。不採用。
- **古いキャッシュを Fallback にする**: BFF にデータを持たせることになる(§8)。在庫は古い値が誤解を招く。不採用。
- **スコープが足りないクエリを実行の前に全体で拒否する**: 実装は簡単だが、同じクエリを権限の違う呼び出しで共有できなくなる。部分応答と同じ形にそろえる。不採用。
- **複雑度をすべての項目で数える**: 既定の数え方(1 + 子)では、正当な最大のクエリが 880 になり、上限 200 を超える。負荷の大きい下流の呼び出しを測りたいので、下流を呼ぶ項目で数える。不採用。
- **利用者のトークンをそのまま下流に転送する、または Token Exchange を使う**: 権限は厳密になるが、`aud` の設計と Keycloak の設定が大きく増える。利用者の経路がまだないので見送る(P13 の判断 3)。
- **上限をコードの定数で持つ**: Framework 5.6 の「制限値は API 契約に明記」を満たせず、契約との二重管理になる。不採用。

## Consequences(トレードオフ)
- contract-check に graphql-java の依存が増える。
- スコープを足すことも破壊的変更になるので、公開した項目の認可を厳しくするときは、例外の手続き(ADR-0013 §6)が要る。
- 出荷の gRPC の上限と SDL の上限の一致は、機械的に確かめていない(§2)。
- 明細の数を 5 と仮定している(§4)。明細の多い注文は実際の負荷が複雑度より大きい。下流の 1 回の上限と Bulkhead で守る。
- 部分応答が続いても HTTP の RED には出ないので、§6 のメトリクスとアラートを見る必要がある。
- graphql-java の正規化は、クエリごとにフラグメントを展開するので、小さくない処理になる。トークンの上限(2000)と展開の上限(2000)で抑える。

## 改訂履歴
- 2026-11-03: P13 ⑦b で下流のクライアントを実装した(`HttpOrderQueries`、`GrpcStockQueries`、`GrpcShipmentQueries`)。§4 に組み込みの Instrumentation の実測の結果を足した。
- 2026-11-03: P13 ⑧a で `app` と複雑度の枠(§9)を足した。§4 にクエリ全体の注文の ID の上限を足した(別名で並べると下流の上限を超えて失敗していた)。
- 2026-11-03: P13 ⑧b で Gateway のルートとクライアントごとの枠、アラート、ダッシュボードを足した(§9)。
- 2026-11-03: P13 ⑨ で E2E(`BffE2E`)を足し、トレースが BFF → Gateway で分かれることを §9 に書いた。
- 2026-11-04: §6 に起動の直後の部分応答を書き、起動時に gRPC の接続を始めるようにした(#199)。
- 2026-11-07: P14 ⑩b の計測で、§9 の複雑度の枠(大きさ 1000・毎秒 60)をそのまま使うと決めた(ADR-0048 §7.2)。
