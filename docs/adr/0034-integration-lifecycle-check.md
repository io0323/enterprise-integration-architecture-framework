# ADR-0034: 連携のライフサイクルの検査
- Status: Accepted
- Date: 2026-10-20
- Framework 参照章: 16.1, 16.2

## Context
Framework 16.1 は、連携を Proposed → Design → Active → Deprecated → Retired の順に進めると定めている。Deprecated にするときは消費者に通知して移行の期限を設け、Retired にするときは資格情報や FW の穴を閉じる。

P02 の contract-check(ADR-0013)は、互換性を「main と同じ相対パスのファイルどうし」で比べる。そのため、次は検出できなかった(#22)。
- カタログのエントリと契約のファイルを同時に消す(参照が切れないので `CC-EVENT-002`・`CC-CATALOG-002` にも当たらない)。
- active のエントリを消す。deprecated を経ずに retired にする。状態を戻す。
- deprecated のまま移行の期限を過ぎた連携(Framework 16 章のゾンビ連携)。

## Decision
contract-check に、比較元(main)のカタログに対するライフサイクルの検査(`CC-LIFECYCLE-*`)を足す。

### 1. 状態の遷移(`CC-LIFECYCLE-001`)
- 状態は proposed → design → active → deprecated → retired の順に、**1 つの PR で 1 段階だけ前に進める**。2 段階以上進める・飛ばす・戻すのは違反。同じ状態のままは自由。
- **新しいエントリは proposed か design で登録する**。deprecated・retired で登録するのは違反。
- **例外 1: 既存の連携の後からの登録**(承認の条件 2)。既に動いている連携をカタログに登録するときだけ、
  `onboarding: { kind: existing, reason: <理由> }` を書いて active で登録できる。onboarding がない・reason がない active の登録は違反。
  カタログの登録が実装より遅れた連携を、実態に合わせて active で記録するため(design から始めると、動いている連携を「設計中」と偽ることになる)。
- **例外 2: 廃止の取り消し**(承認の条件 1)。deprecated → active に戻すことを、
  `reactivation: { reason: <理由>, approver: <承認者> }` を書いたときだけ許す。書いていなければ違反。
  移行先の連携の廃止が中止された、など、消費者に通知した廃止を取り消す正当な理由はありうる。理由と承認者を残し、レビューで確かめられるようにする。
  承認者は GitHub のアカウント(`@name`)かチーム(`team-name`)。
- deprecated → retired は、**比較元の `deprecation.sunset` の後だけ**(`CC-LIFECYCLE-004`)。移行の期限の前に廃止すると、移行中の消費者を壊す。

### 2. 削除(`CC-LIFECYCLE-002`・`003`)
- カタログのエントリを消せるのは、**比較元で retired のものだけ**。retired にする PR と消す PR は分ける(retired のレビューで後片付けを確かめてから消す)。
- **比較元で retired 以外の連携が使う契約のファイルは消せない**。対象は、カタログの `contract`(OpenAPI・AsyncAPI・ファイルの仕様)と、AsyncAPI の payload が参照する Avro。
  新しい版は別のファイル(`v{n+1}`)で足し、古い版を使う連携を廃止の手順に乗せる。
- **どの連携も使っていない契約のファイル**は消してよい。存在自体を違反にする規則は足さない。OpenAPI・AsyncAPI・ファイルの仕様は、既に `CC-CATALOG-006`・`CC-FILE-004` でカタログへの登録を必須にしている。Avro は AsyncAPI から参照されていなくても、共通の型などで置かれうる。

### 3. 状態ごとの項目(カタログスキーマ。`CC-CATALOG-001`)
- deprecated: `deprecation: { sunset: YYYY-MM-DD, notice: "#Issue", replacement: INT-... }`。sunset は移行の期限、notice は消費者への通知の Issue、replacement は移行先(任意)。
- retired: `retirement: { issue: "#Issue" }`。資格情報・ACL・FW の穴を閉じる後片付けの Issue。
- `onboarding`・`reactivation` は上の例外のときに書く(形式はスキーマで検査し、使える場面は `CC-LIFECYCLE-001` で検査する)。

### 4. 期限を過ぎた deprecated の警告(`CC-LIFECYCLE-005`。承認の条件 3)
- `deprecation.sunset` を過ぎても deprecated のままの連携(ゾンビ連携)は、**違反ではなく警告** として出す。出力の末尾と、GitHub Actions の Step Summary の「警告」の表に出す。**ジョブは失敗させない**。
- 違反にしない理由: 期限の到来は PR の変更と関係なく起きるので、違反にすると無関係な PR の CI が失敗する(互換性検査の例外の期限切れ `CC-WAIVER-002` と違い、直す手順が「消費者の移行を待つ」で、すぐには直せない)。
- 期限の当日はまだ警告しない(当日までは移行の期間)。判定の日付は UTC。

### 5. 比較元がないとき
- 状態の遷移と削除の検査(001〜004)は、互換性検査と同じく省略し、出力に省略したことを書く。警告(005)は比較元が要らないので出す。

## Alternatives Considered
| 案 | 採らない理由 |
|---|---|
| 状態を何段階でも進めてよい(retired への到達だけを見る) | deprecated を飛ばすと、消費者への通知と移行の期限が記録に残らない(Framework 16.1) |
| 廃止の取り消しを一律に禁止する | 移行先の廃止の中止など、正当な理由がありうる。禁止すると、別の ID で登録し直すなど記録が途切れる |
| 後からの登録も design から始めさせる | 動いている連携を「設計中」と記録することになり、カタログが実態とずれる |
| 期限を過ぎた deprecated を違反にする | 期限の到来は PR と関係なく起き、無関係な PR の CI を止める。すぐに直せない |
| 参照されていない Avro を違反にする | 共通の型など、AsyncAPI から直接参照されない Avro がありうる。登録の検査は OpenAPI・AsyncAPI・ファイルの仕様で足りる |

## Consequences(トレードオフ)
- 連携を廃止するには、deprecated(通知と期限)→ 期限の後に retired(後片付け)→ 消す、の 3 つの PR が要る。手順は増えるが、各段階がレビューに残る。
- 状態の遷移は比較元(main)と比べるので、main に直接の変更があった場合(Ruleset で禁止している)や、比較元を取り出せない場合は検査されない。
- 判定は連携 ID で行う。ID を変えると、古い ID の削除と新しい ID の登録になる(古い ID が retired でなければ違反)。
- 承認者の記載は形式だけを検査し、実在や権限は検査しない(レビューで確かめる)。

## 改訂履歴
- 2026-10-20: P09 ⑧a(#22)で作成した。互換性検査と状態の連動・active な連携への例外の条件は ⑧b で ADR-0013 を改訂する。
- 2026-10-21: P09 ⑧b(#22)で、互換性の検査を状態と連動させ(設計中の連携だけが使う契約の破壊的変更を許す)、active・deprecated の連携の契約への例外の条件(`CC-WAIVER-003`)を足した(ADR-0013 §6・§7)。契約を使う連携の判定(`ContractUsage`)は、削除の検査と共用する。
