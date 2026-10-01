# ADR-0012: Canonical Model の Avro での物理表現と一致検査
- Status: Accepted
- Date: 2026-09-26
- Framework 参照章: 6.3, 15.1, 15.3, 15.4

## Context
- Canonical Model(`shared/canonical-model`、KMP の commonMain)と Avro スキーマ(`contracts/avro`)は別々の資産になる(ADR-0004 の Consequences)。avro4k は JVM 専用で、commonMain から Avro を生成できないため。
- ADR-0011 §1 は Money の Avro での表現を P02 に持ち越した。候補は「最小通貨単位の long と通貨コードの組」と「decimal 論理型」。
- 時刻は `kotlin.time.Instant`(ナノ秒精度)で、Avro の timestamp 論理型はミリ秒かマイクロ秒しか表せない。精度を落とす規則を決めないと、送信側と受信側で値がずれる。
- 2 つの資産は、放っておくと項目名・必須性・型がずれていく。ずれは実行時(デシリアライズ失敗や項目の欠落)まで見つからない。

## Decision
### 1. 変換規則
Canonical Model(Kotlin)の型と Avro の型を、次の表のとおりに対応させる。表にない型は Avro で運ばない(運ぶ必要ができたら、この表に規則を追加する)。

| Kotlin(Canonical Model) | Avro | 規則 |
|---|---|---|
| `String` | `string` | そのまま |
| `Int` | `int` | そのまま。`long` にしない |
| `Long` | `long` | そのまま |
| `Boolean` / `Float` / `Double` | `boolean` / `float` / `double` | そのまま。ただし金額・率に浮動小数点は使わない(ADR-0011) |
| `enum class` | `enum` | 値(symbols)の集合が一致する。並び順は問わない |
| `@JvmInline value class`(ID など) | 中身の型 | 例: `OrderId(String)` → `string` |
| `List<T>` | `array<T>` | 要素を同じ規則で変換する |
| `Map<String, T>` | `map<T>` | キーは `String` のみ |
| `data class` | `record` | 項目名の集合が一致する。record 名は問わない |
| `T?` | `["null", T]` | nullable と union(null を含む 2 分岐)が対応する。non-null の項目に null を許してはならない |
| 既定値を持つ項目(`= null` など) | `"default"` あり | Kotlin で任意の項目は Avro でも default を持つ(FULL 互換のため。ADR-0014) |
| **`Money`** | **`record Money { minorUnits: long, currency: string }`**(namespace `io.eia.events.common`) | 次の §2 |
| **`kotlin.time.Instant`** | **`long` + `timestamp-micros`** | 次の §3 |

### 2. Money
- Avro では `record Money { minorUnits: long, currency: string }` で表す。`minorUnits` は `io.eia.shared.kernel.money.Money.minorUnits` と同じ名前・同じ値(最小通貨単位)で、`currency` は ISO 4217 のコード。
- 小数桁数は運ばない。受信側は `CurrencyResolver` で通貨コードから引く(ADR-0011 §2)。
- JSON の `{"amount": "1234.50", "currency": "USD"}`(ADR-0011 §1)は別の物理表現で、この一致検査の対象外とする。

### 3. Instant
- Avro では `long` + 論理型 `timestamp-micros`(UTC エポックからのマイクロ秒)で表す。
- **マイクロ秒未満は切り捨てる。** 切り捨ては時間軸の過去方向(負の無限大方向)とし、エポック以前の時刻でも同じ規則にする(`1969-12-31T23:59:59.999999999Z` → `-1`)。
- `long` のマイクロ秒で表せない時刻(約 ±29 万年の外)はエラーにする。
- 切り捨ては `shared/kernel` の `Instant.truncatedToMicros()` に置き、domain(受け付けた時刻の切り捨て)と次の変換関数の両方がこれを使う。
- 変換関数は `shared/canonical-model` の `Instant.toEpochMicros()` と `instantOfEpochMicros()`(commonMain)に置く。ナノ秒を含む値の往復・エポック以前・範囲の端は `EpochMicrosSpec` で検査する。

### 4. 一致検査(contract-check の `CC-CANON-*`)
- `tools/contract-check` の `CanonicalAvroConformance` が、kotlinx.serialization の `SerialDescriptor` と Avro スキーマを再帰的に比べる。名前・必須性・型のどれかが食い違えば失敗する。変換規則のない型が現れた場合も失敗する。
- 対象の組は `CanonicalBindings` に列挙する(現在は `sales.Order` ⇔ `io.eia.events.sales.Order`)。入れ子の型は親の検査の中で再帰的に検査する。
- JVM のテスト(`./gradlew build`)と CI の contract-check の両方で実行する。

## Alternatives Considered
- **Money を decimal 論理型(bytes + precision/scale)にする**: scale はスキーマごとに固定する必要がある。通貨によって小数桁数が違う(JPY は 0、USD は 2、BHD は 3)ため、1 つのスキーマで複数の通貨を正しく表せない。scale を最大桁数に合わせると、JPY の値に小数部を持ててしまう。不採用。
- **Money を 10 進文字列と通貨コードの組(JSON と同じ)にする**: 形式を揃えられるが、文字列の検証を受信側が毎回行う必要があり、Avro の型で値域を守れない。不採用。
- **Instant を `timestamp-millis` にする**: Debezium(P06)は PostgreSQL の timestamp をマイクロ秒で出力するため、CDC 経由のイベントとミリ秒のイベントで精度が混在する。不採用。
- **Instant を `timestamp-nanos`(Avro 1.12 で追加)にする**: Apicurio / Debezium / 他言語の Avro ライブラリの対応がまだ揃っていない。不採用(対応が揃ったら再評価する)。
- **マイクロ秒未満を四捨五入する**: 丸めた結果が元の時刻より未来になることがあり、「発生した時刻より後に記録される」という前提が崩れる。0 方向への切り捨て(エポック以前で未来方向になる)も同じ理由で採用しない。
- **Avro から Kotlin のクラスを生成する**: 生成物は JVM 専用になり、Canonical Model(KMP)の代わりにならない。一致検査で十分に担保できる。不採用。

## Consequences(トレードオフ)
- Instant はナノ秒の精度を失う。業務上マイクロ秒未満の精度が必要な時刻は、別の項目(例: 連番)で順序を表す。
- Canonical Model の型を増やすときは、変換規則の表と `CanonicalBindings` の更新が必要になる。更新を忘れると、変換規則のない型として検査が失敗するので気付ける。
- Money の小数桁数を受信側が知っている必要がある。未知の通貨コードは受信点でエラーになる(ADR-0011 §5 と同じ扱い)。
