# ADR-0011: Shared Kernel と Canonical Model の表現規約
- Status: Accepted
- Date: 2026-09-25
- Framework 参照章: 5.4, 5.5, 13, 15

## Context
P01 で `shared/kernel` と `shared/canonical-model`(KMP: jvm / js / linuxX64 / macosArm64)を実装する。設計書と標準だけでは決まらない点がいくつかある。
- 金額: KMP の common には `BigDecimal` がない。一方で、通貨を暗黙の前提にしたり、浮動小数点で計算したりすることは Framework 15.4 がアンチパターンとしている。
- 丸めと按分: 税額や割引額の計算で暗黙の丸めがあると、送信側と受信側で 1 円ずれる。
- 請求の税計算: インボイス制度(適格請求書等保存方式)では、消費税の端数処理は「1 つの請求書につき、税率ごとに 1 回」と定められている。
- リトライ回数: Framework 5.5 の「最大3回」はリトライの回数とも、試行の回数とも読める。INTEGRATION_STANDARDS §3 は「max 3 attempts」と書いている。
- エラーの分類: CODING_STANDARDS は `DomainError` を sealed interface にするよう定めている。一方で、各サービスは独自のエラーを定義する必要があり、sealed interface の直下の型は同じモジュールにしか置けない。
- 検証: kotlinx.serialization のデコードはファクトリ関数を通らない。デコードした後に `validate()` を呼び忘れると、不整合なデータが業務ロジックまで届いてしまう。
- 時刻: kotlinx-datetime 0.7 以降は、`Instant` と `Clock` が標準ライブラリの `kotlin.time` に移った。Kotlin 2.4.20 では opt-in なしで使えることを確認した。

## Decision
### 1. Money(論理モデル)と物理表現
- `io.eia.shared.kernel.money.Money` は論理モデルとしての金額で、**最小通貨単位の Long**(JPY なら円、USD ならセント)と `Currency` の組で表す。
- 論理モデルと、フォーマットごとの物理表現は別物として扱う。
  - JSON(Canonical Model): `{"amount": "1234.50", "currency": "USD"}`。amount は 10 進の文字列で、常に通貨の小数桁数で書き出す。
  - Avro: P02 で決める(候補は、最小通貨単位の long と通貨コードの組、または decimal 論理型)。
- パースでは、小数部が通貨の小数桁数を超える値(JPY の `"100.5"`、USD の `"1.005"`)を**丸めずにエラー**にする。指数表記・桁区切り・前後の空白も受け付けない。
- 通貨が異なる金額どうしの演算は `MoneyError.CurrencyMismatch`、Long の範囲を超える演算は `MoneyError.Overflow` を返す。値が黙って変わることはない。

#### 方式の比較
| 評価軸 | 最小通貨単位の Long(採用) | KMP 対応の多倍長演算ライブラリ(例: ionspin `bignum`) |
|---|---|---|
| オーバーフロー | 約 9.2×10^18 最小単位が上限(USD で約 92 京ドル)。上限を超える演算は `Overflow` エラーで検出し、黙って桁あふれすることはない | 桁あふれしない |
| 性能 | プリミティブの演算。割り当ては Money オブジェクト 1 つだけ | 演算のたびにオブジェクトを割り当てる。js / native では特に遅い |
| 依存 | なし(stdlib のみ。kernel の依存ゼロを保てる) | 外部ライブラリ。Kotlin や K/N の新版への追従が単一のメンテナに依存する |
| js / native 対応 | 全ターゲットで動く(Kotlin/JS の Long も正確に計算される) | 対応しているが、バンドルサイズが増える |
| 丸め・按分 | kernel に実装する(`divideRounded` は JVM テストで `BigDecimal` と一致することを確認する) | ライブラリの機能を使える |

取引・請求・明細の金額は Long の範囲に十分収まり、範囲外は必ずエラーとして検出できる。性能と依存ゼロを優先し、Long を採用する。多倍長演算ライブラリは、依存の追加と js / native での性能低下に見合う利点がないため採用しない。分析基盤で集計する巨大な合計値は、DB の `numeric` 型で扱い、kernel の対象外とする。

### 2. Currency
- ISO 4217 のコードと小数桁数(0〜4)を持つ値オブジェクトとする。enum にはしない。プロパティが 2 つあるため `@JvmInline value class` にはできず、通常のクラスで値の等価性を実装する。
- よく使う通貨(JPY / USD / EUR / GBP / CNY / KRW)は定数で用意する。それ以外は `Currency.of(code, digits)` で生成する。
- コードだけを運ぶ表現(JSON)から復元するときは `CurrencyResolver` で引く。扱う通貨を増やすときは Resolver を差し替えるだけでよく、kernel は変更しない。

### 3. 率・丸め・按分
- 率(税率・割引率)は浮動小数点を使わず、既約分数の `Rate(numerator / denominator)` で表す。basis points(1 万分の 1 単位)からも作れる(`Rate.basisPoints(1000)` = 10%)。
- 率を掛ける演算 `Money.times(rate, rounding)` は、`RoundingMode`(UP / DOWN / CEILING / FLOOR / HALF_UP / HALF_DOWN / HALF_EVEN。意味は `java.math.RoundingMode` と同じ)を**必ず引数で受け取り、既定値を持たない**。暗黙の丸めは禁止する。
- 按分 `Money.allocate(weights)` は最大剰余法で行う。各配分を 0 方向に切り捨て、残った最小単位を、切り捨てた剰余の大きい順に 1 単位ずつ配る。剰余が同じなら先頭側を優先する。重み 0 の相手には常に 0 を配る。**配分の合計は必ず元の金額に一致する**(property test で検証する)。

### 4. 請求の税計算(billing)
- 価格はすべて税抜で表す。税込価格からの逆算は範囲外とする(必要になった時点で ADR を追加する)。
- 税額の計算規則 `TaxCalculationRule` は、丸める単位(granularity)と丸め方(roundingMode)の組とする。
  - 既定の丸める単位は `PER_INVOICE_PER_RATE`(インボイス制度に準拠): 税率ごとに明細の税抜金額を合計し、その合計に税率を掛けて 1 回だけ丸める。
  - 代わりに `PER_LINE` も選べる: 明細ごとに税額を丸め、税率ごとに合計する。インボイス制度の適格請求書では使えないため、海外の取引先やレガシー連携の互換用に限る。
  - 丸め方は既定値を持たず、必ず明示する(§3)。日本の商慣行では `DOWN`(切り捨て)が多い。
- 規則は Invoice 自身に持たせ、どの規則で計算したかを受信側が再計算して検証できるようにする。
- `Invoice.validate()` は、規則に従って税率別の対象額と税額を再計算し、Invoice に書かれた税率別の集計・小計・税額合計・総額と一致するかを検証する。

### 5. Canonical Model の構成と検証の入口
- パッケージはドメイン単位に分ける: `io.eia.shared.canonical.{common, sales, catalog, billing, logistics}`。全社で 1 つの巨大なモデルにはしない(Framework 15.4)。
- 時刻は `kotlin.time.Instant`(UTC)、金額は `Money`(通貨を明示)で表す。
- 各エンティティは `validate(): Result<T, ValidationError>` を持つ。値域と業務整合の違反を**すべて集めて**返す。
- デシリアライズと `validate()` は `CanonicalCodec.decode<T>(json): Result<T, DomainError>` で必ず一緒に行う。**受信点ではこの入口を使う**(CODING_STANDARDS に記載)。`encode` も `validate()` を通すため、不整合なデータは送信もできない。
- 前方互換のため、未知のフィールドは無視する(BACKWARD 互換の方針。INTEGRATION_STANDARDS §4)。

### 6. DomainError の 2 層構造
- `DomainError` は sealed interface とし、直下の子を `Retryable` と `NonRetryable` の 2 つのインターフェースだけにする。この 2 つは sealed にしないので、各サービスが自分のエラーを実装できる。一方で、`when` は 2 分岐で網羅できる。
- 1 つの型が `Retryable` と `NonRetryable` の両方を(間接的な継承も含めて)実装することは Konsist で禁止する(ADR-0010 §6)。
- `catching {}` は `CancellationException` を再送出し、それ以外の `Exception` を `UnexpectedError`(NonRetryable)に変換する。Retryable に分類したい場合は、分類関数を受け取る版を使う。

### 7. RetryPolicy
- `maxAttempts` は**初回を含む試行回数**とし、既定は 3(初回 + リトライ 2 回)。Framework 5.5 と INTEGRATION_STANDARDS §3 の文言をこれに合わせる。
- リトライするのは `DomainError.Retryable` だけとする。`retryAfter`(Retry-After)があればバックオフより優先する。ただし `maxDelay` を超える場合は、タイムバジェットを超えるため打ち切る。
- 待ち時間の計算は純粋な関数とし、乱数は引数で受け取る。

### 8. 時刻
- 時刻の Port には標準ライブラリの `kotlin.time.Clock` を使い、型は `kotlin.time.Instant` とする。kernel は `Clock` の独自インターフェースを定義しない。テスト用に `FixedClock` を提供する。
- kotlinx-datetime は、`LocalDate` やタイムゾーン変換が必要になったときだけ使う。

## Alternatives Considered
- Money を `Double` で保持する: 誤差が出るため不採用。
- Money を 10 進の文字列のまま保持し、演算のたびにパースする: 性能が悪く、演算の実装が複雑になるため不採用。
- 多倍長演算ライブラリを使う: §1 の比較表のとおり不採用。
- 丸め方に既定値(HALF_EVEN など)を持たせる: 呼び出し側が丸めを意識しなくなり、システム間で 1 円ずれる原因になるため不採用。
- `DomainError` を sealed にせず open な interface にする: `when` で網羅できなくなるため不採用。
- デシリアライズ時に init ブロックで検証し、例外を投げる: domain で例外を制御に使うことになり、違反をまとめて返せないため不採用。

## Consequences(トレードオフ)
- 金額の上限は約 9.2×10^18 最小単位になる。超える演算はエラーとして検出するが、合計値の集計では呼び出し側でエラーを扱う必要がある。
- 率を掛ける演算のたびに丸め方を書く必要があり、記述量は増える。代わりに丸めの仕様がコード上で明示される。
- Canonical Model(Kotlin)と Avro スキーマ(contracts)は別々の資産として管理する。一致の検査は P02 で行う。
- 継承関係を型の単純名で辿るため、同名の別の型があると Konsist の検査が誤検知しうる。

## 改訂履歴
- 2026-09-26: Money の Avro での表現(§1 で P02 に持ち越した点)は ADR-0012 で決定
