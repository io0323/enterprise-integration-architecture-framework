# ADR-0042: 社内の照会の gRPC(契約・検査・範囲)
- Status: Accepted
- Date: 2026-11-02
- Framework 参照章: 3.1, 5.1, 5.3, 12.1, 13, 14, 15
- 関連: ADR-0008(mTLS の範囲)、ADR-0013(contract-check。ツールの固定)、ADR-0021(Resilience・CallDeadline)、ADR-0023(ゼロトラストの考え方)、ADR-0024(リクエストの予算)
- 番号: P13 の計画で決めた番号(0042 gRPC / 0043 GraphQL BFF)
- 書く順: P13 ① で §1〜§4(契約と検査)を書く。接続・締め切り・状態コード・認証は P13 ② で §5 以降に足す

## Context
P13(#16)で、BFF が画面のために注文・在庫・出荷を集約する(ADR-0043)。inventory と shipping には、これまで同期の読み取り口がなかった(Kafka のコマンドと、平文のヘルスチェックのポートだけ)。Framework 5.1 は、社内の低遅延の通信に gRPC を割り当て、社外には公開しない。

ここでは、契約の範囲と形、置き場所と命名、破壊的変更の検査を決める。

## Decision
### 1. 範囲: inventory と shipping の照会
- ROADMAP P13 は inventory の在庫照会だけを挙げているが、**shipping の出荷照会も gRPC で公開する**。BFF の集約には出荷が要り、shipping には読み取り口がないため。ROADMAP を直した。
- どちらも**読み取りだけ**にする。在庫と出荷を変えるのは、これまでどおり注文 Saga のコマンド(ADR-0029)だけ。
- 注文は gRPC にしない。BFF から Gateway 経由の REST で読む(ADR-0043)。同じ読み取りの契約を 2 つ持たないため。

### 2. 置き場所と命名
| 対象 | 規則 | 例 |
|---|---|---|
| ファイル | `contracts/proto/io/eia/{domain}/v{n}/{name}.proto` | `contracts/proto/io/eia/inventory/v1/stock_query_service.proto` |
| package | `io.eia.{domain}.v{n}`(版を含める) | `io.eia.inventory.v1` |
| `java_package` | `io.eia.platform.grpcstubs.{domain}.v{n}` | `io.eia.platform.grpcstubs.inventory.v1` |
| カタログ | 1 ファイル 1 連携(`style: grpc`) | INT-INVENTORY-003・INT-SHIPPING-003 |

- `java_package` を package と分けるのは、サービスのパッケージ(`io.eia.inventory`)のままだと、BFF が生成したスタブを import したときに、サービスの分離の Konsist(`serviceIsolation`)に反するため。スタブは 1 か所(`platform/grpc-stubs`。P13 ②)で生成して共有する。
- proto は外部のレジストリの依存を持たない。import してよいのは Protobuf の標準の型(`google/protobuf/*`)だけ(buf に同梱で、ネットワークを使わない)。

### 3. 検査は buf(版とチェックサムを固定)
| 候補 | 評価 |
|---|---|
| **buf 1.75.0(採用)** | lint と破壊的変更の検査が 1 つの単一バイナリにそろう。JSON で結果を受け取れる。`deps` を書かなければ外部に接続しない。JVM のプロセスから oasdiff と同じ形で動かせる |
| protoc で記述子を作って自前で比べる | 規則(番号・型・名前・削除・パッケージ・オプション)の網羅を自分で持つことになる。ADR-0013 で oasdiff を選び、規則の自作を退けた理由と同じ。不採用 |
| buf を Docker イメージで動かす | `./gradlew build` のテストで Docker が必須になる(ADR-0013 と同じ理由)。不採用 |

- 取得は oasdiff と同じ扱い(ADR-0013 §3)。版は `libs.versions.toml` の `buf`、プラットフォームごとの SHA-256 は `tools/contract-check/build.gradle.kts` に固定し、一致しなければビルドを失敗させる。
- 規則は `contracts/proto/buf.yaml` に置く。
  | ルール | buf | 内容 |
  |---|---|---|
  | `CC-STRUCT-006` | `buf lint`(STANDARD) | package の版、ディレクトリとの一致、命名、構文 |
  | `CC-COMPAT-006` | `buf breaking`(FILE) | main の版からの破壊的変更。FILE は最も厳しい区分で、ワイヤの互換だけでなく、生成コードの互換(項目名・パッケージの変更)も守る。社内の利用者はスタブで動くため |
  | `CC-NAMING-013` | (contract-check) | 置き場所と `java_package`。buf は `io.eia` の接頭辞と Java のパッケージの決まりを知らない |
- buf の振る舞い(1.75.0 で確かめた): 違反は終了コード 100 で JSON を 1 行に 1 件、ツールの失敗は終了コード 1。比較元に proto がないと失敗するので、そのときは新規として検査しない。proto がすべて消えたときも buf を使えないので、contract-check が消えたファイルごとに違反にする。ファイルの削除の報告には `path` がないので、文から取り出す(互換性の違反の許可は、ファイルの単位で判定するため)。
- 設計中の連携の扱いと例外の扱いは、ほかの契約と同じ(ADR-0013 §6・§7)。

### 4. 形: まとめて読む RPC だけにする
| RPC | 1 回の上限 | 見つからないとき |
|---|---|---|
| `StockQueryService.BatchGetStock` | SKU 500 件 | `unknown_skus` に返す |
| `ShipmentQueryService.BatchGetShipments` | 注文 20 件 | 応答に含めない(まだ手配していないか、保持期間を過ぎた) |

- 1 件ずつ読む RPC は持たない。呼び出し側に N+1 の書き方をさせないため(ADR-0043)。
- **「ない」と「読めなかった」を区別する**。ないことは正常な応答で、読めなかったことは gRPC の状態コードで返す。呼び出し側は、在庫の照会の失敗を「在庫がない」として表示してはいけない。
- 注文 20 件は、注文の API のまとめて読む口の上限にそろえる(ADR-0043)。SKU 500 件は、注文の明細の数に契約の上限がないため、呼び出し側が件数で分割する前提の 1 回の上限にする。
- `available`(引当できる数)は inventory が計算して返す。呼び出し側で計算しない(ADR-0043 の BFF の線引き)。読んだ時点の値で、引当を保証しない。
- 出荷が返すのは国までで、住所を持たない(shipping の記録と同じ。Framework 12.2)。Saga の内部の印(取消済み)は返さない。

## Alternatives Considered
- **shipping を REST で公開する**: Gateway の公開面と Rate Limit の枠が増える。社内の読み取りだけなので不採用。
- **shipping の情報を出さず、注文の状態で代用する**: 出荷の ID を返せず、ROADMAP の「出荷」を満たせない。不採用。
- **1 件ずつ読む RPC も用意する**: 使い方を 1 つにするほうが、N+1 を防げる。不採用。
- **`buf breaking` の区分を WIRE_JSON にする**: 項目名の変更を通してしまい、生成したスタブのコードが壊れる。不採用。

## Consequences(トレードオフ)
- `./gradlew build` のテストと contract-check の実行で、初回に buf を GitHub Releases から取得する(oasdiff と同じ)。
- 版を上げるときは、`libs.versions.toml` とチェックサムの表の両方を直す。
- FILE は厳しいので、項目名の修正も破壊的変更になる。設計中のうちに名前を固める。

## 改訂履歴
