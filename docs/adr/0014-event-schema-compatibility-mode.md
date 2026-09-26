# ADR-0014: イベントスキーマの互換性モード
- Status: Accepted
- Date: 2026-09-26
- Framework 参照章: 6.3, 13, 15.3

## Context
- Framework 6.3 は「BACKWARD 互換必須(消費者を先に更新せず発行者を進化可能)」と書いている。括弧内の「発行者を先に更新できる」は、Schema Registry の用語では **FORWARD** 互換(古いスキーマの reader が、新しいスキーマで書かれたデータを読める)の性質にあたる。INTEGRATION_STANDARDS §4 と P02 の contract-check は、語のとおり **BACKWARD**(新しいスキーマの reader が、古いスキーマで書かれたデータを読める)で実装していた(ADR-0013 の未解決の点)。
- 本リポジトリの連携には、次の 2 つが両方とも必要である。
  - **発行側を先に更新できること**(Framework 6.3 の意図): Outbox + Debezium で発行する側(P06)は、消費者のデプロイを待たずに新しいスキーマで発行を始める。古い消費者が新しいイベントを読めなければならない(FORWARD)。
  - **過去のイベントの Replay**(Framework 13 章): DLQ の再処理やトピックの再読込では、新しいスキーマの消費者が、古いスキーマで書かれたイベントを読む。新しい消費者が古いイベントを読めなければならない(BACKWARD)。
- コマンドトピック(ADR-0006)も、送信側と受信側が別々にデプロイされ、DLQ から Replay する点でイベントと同じ性質を持つ。

## Decision
### 1. 互換性モード
- 業務イベントのトピックとコマンドのトピック(`{domain}.{entity}.{event}.v{n}`、`{domain}.{entity}.cmd-{command}.v{n}`)のスキーマは **FULL 互換**(BACKWARD かつ FORWARD)を要件とする。
- FULL を満たさない変更は、新しいバージョンのトピック(`.v{n+1}`)で行い、並行稼働してから旧トピックを廃止する(Framework 6.3)。

### 2. 検査する場所
| 段階 | 比較の相手 | モード | 強制する仕組み |
|---|---|---|---|
| CI(P02〜) | origin/main の同じファイル(1 つ前の版) | FULL | contract-check の `CC-COMPAT-001`(ADR-0013) |
| Schema Registry への登録(P03〜) | 登録済みの全バージョン | FULL_TRANSITIVE | Apicurio の互換性ルール(トピック単位) |

- CI は 1 つ前の版とだけ比べる。main は常に FULL を満たしてきたので、実質的には直近の版との互換性が保たれる。ただし「default 付き項目の削除 → 同名で型の違う項目の追加」のように、2 段階を経ると過去の版と非互換になる変更は CI では検出できない。これは P03 以降、Schema Registry の FULL_TRANSITIVE で登録時に拒否する。

### 3. FULL のもとで許される変更と許されない変更
| 変更 | BACKWARD | FORWARD | FULL | 備考 |
|---|---|---|---|---|
| default 付きの任意項目の追加 | ○ | ○ | **可** | 新しい reader は古いデータに default を補い、古い reader は未知の項目を読み飛ばす |
| default 付きの項目の削除 | ○ | ○ | **可** | 古い reader は欠けた項目に default を補う |
| doc の変更 | ○ | ○ | **可** | |
| default のない項目の追加 | × | ○ | 不可 | 新しい reader が古いデータの項目を埋められない |
| default のない項目の削除 | ○ | × | 不可 | 古い reader が新しいデータの項目を埋められない |
| 項目の型の変更(`string` → `long` など) | × | × | 不可 | |
| 型の拡張(`int` → `long`) | ○ | × | 不可 | 古い reader は `long` を `int` として読めない |
| 項目名の変更(aliases を付けても) | ○ | × | 不可 | aliases は新しい reader にしか効かない |
| nullable から必須へ(`["null", T]` → `T`) | × | ○ | 不可 | |
| 必須から nullable へ(`T` → `["null", T]`) | ○ | × | 不可 | |
| enum の値の削除 | × | ○ | 不可 | |
| enum の値の追加 | ○ | × | 不可 | 古いスキーマの enum に `default` がある場合だけ FORWARD も満たす。現在の契約の enum は default を持たない |

### 4. contract-check の変更
- `CC-COMPAT-001` を FULL 検査にする。Avro の `SchemaCompatibility` で「新しいスキーマを reader、main のスキーマを writer(BACKWARD)」と「main のスキーマを reader、新しいスキーマを writer(FORWARD)」の両方を検査し、違反の内容に `BACKWARD:` / `FORWARD:` のどちらで壊れたかを付ける。
- FORWARD だけが壊れる変更(default のない項目の削除、enum の値の追加)の違反サンプルを追加する。

## Alternatives Considered
- **BACKWARD のまま**: Replay はできるが、発行側を先に更新すると古い消費者が失敗する。Framework 6.3 の意図(発行者を先に進化させる)を満たさない。不採用。
- **FORWARD にする**: 発行側を先に更新できるが、新しい消費者で過去のイベントを Replay したときに読めない場合がある(default のない項目の追加が許される)。Framework 13 章の Replay の要件を満たさない。不採用。
- **CI でも FULL_TRANSITIVE(全履歴との比較)にする**: git の履歴から全版を取り出して比較できるが、履歴の書き換えや過去の誤った版があると CI が恒久的に失敗する。登録済みの版を正とする Schema Registry に任せるほうが、実際に配信されたスキーマと比べられる。不採用。

## Consequences(トレードオフ)
- 変更できる範囲が狭くなる。項目の追加・削除は default 付きの項目に限られ、それ以外は新しいバージョンのトピックになる。
- enum に値を追加する可能性がある項目は、最初から enum の `default`(未知の値を表す値)を持たせるか、string で表す必要がある。現在の `OrderStatus` は default を持たないため、値の追加は新しいバージョンのトピックで行う。
- FULL_TRANSITIVE の強制は P03(Apicurio の導入)まで CI の FULL だけで代替する。
