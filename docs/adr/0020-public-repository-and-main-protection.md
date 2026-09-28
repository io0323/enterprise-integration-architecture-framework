# ADR-0020: リポジトリの公開と main の保護
- Status: Accepted
- Date: 2026-09-28
- Framework 参照章: 12, 16, 19
- 関連: ADR-0008(Secrets の縮退)、ADR-0016 §6(秘密情報の置き場所)

## Context
このリポジトリは private で運用してきた。次の 2 つの問題があった。

1. **CI が動かない**
   - private リポジトリの GitHub Actions は、無料枠を超えると課金が必要になる。
   - 2026-09-27、PR #38 の CI が、課金の失敗で 1 つも起動しなかった。3 つのジョブがランナーを割り当てられず、2 秒で失敗した。
   - CLAUDE.md §6 は「CI が成功していない PR はマージしない」としているため、CI が動かないと作業が止まる。
2. **main を仕組みで保護できない**
   - GitHub Free の private リポジトリでは、Ruleset もブランチ保護も使えない。
   - そのため main の保護は、pre-push フック(手元の設定で外せる)と運用(CLAUDE.md §6)だけで担保していた。

## Decision
### 1. リポジトリを公開する
公開したのは 2026-09-28。理由は次の 2 つ。
- 公開リポジトリの標準の GitHub ホストのランナーは無料で、課金の状態に左右されない。
- 公開リポジトリでは、GitHub Free でも Ruleset を使える。

### 2. main を Ruleset で保護する
Ruleset `main protection`(ID 24095263)を設定した。

| 項目 | 設定 |
|---|---|
| 対象 | 既定のブランチ(main) |
| 有効化 | active |
| 削除 | 禁止 |
| force push | 禁止 |
| 直線履歴 | 必須(マージコミットを作らない) |
| PR | 必須。承認の必要数は 0。マージの方式は rebase だけ |
| 必須チェック | `build`(GitHub Actions が報告したものだけ有効) |
| 最新のブランチであること | 求めない(`strict_required_status_checks_policy: false`) |
| バイパス | なし(管理者も対象) |

- **承認の必要数を 0 にした理由**: 1 人で開発しているため。1 以上にすると、自分の PR を承認できず、マージできなくなる。PR を必須にすること自体(main に直接 push できないこと・必須チェックを通すこと)は仕組みで強制する。レビューは、これまでどおり CodeRabbit と `/review-integration` で行う。
- **マージの方式を rebase だけにした理由**: PR の中のコミットを、Conventional Commits の単位のまま main に残すため。squash は PR を 1 コミットにまとめるため、PR の中で分けたコミット(機能・テスト・ADR など)の区切りが main から消える。main への追従も、PR の画面の「Update branch(rebase)」で行っており、方式を揃える。リポジトリの設定ではマージコミットと squash のボタンも有効のままだが、Ruleset がその方式でのマージを拒否する。
- **最新のブランチであることを求めない理由**: main への追従は、PR の画面の「Update branch(rebase)」で行う運用にしている。求めると、main が進むたびに、すべての PR で追従と CI のやり直しが必要になる。直線履歴を必須にしているため、マージコミットは作られない。
- **pre-push フックは残す**(多層防御)。push の前に手元で止められるため、誤った push が GitHub に届く前に気づける。
- **バイパスをなしにした理由**: 管理者でも、Ruleset を明示的に変えない限り main を書き換えられないようにする。緊急時は、Ruleset を一時的に無効にしてから対応し、対応が終わったら戻す。

### 3. 必須チェックは `build` だけにする
- `build` は、ci の workflow の中で条件なしに毎回実行される。ci は、すべての PR で起動する。
- ほかのジョブは、変更のあったパスによって実行されたりされなかったりする。
  - `integration` と `macos`: ジョブの `if` による。
  - contract-check と infra: workflow の `paths` による。
- workflow の `paths` で起動しなかった workflow は、チェックを報告しない。これを必須にすると、対象外の PR がいつまでもマージできなくなる。
- 実行された場合に、それらのジョブが成功していることは、運用で確かめる(CLAUDE.md §6)。
- 必須チェックの送り元を GitHub Actions(`integration_id` 15368)に固定した。別の GitHub App や、コミットのステータスの API から `build` という名前のチェックを報告されても、条件を満たさない。

### 4. 秘密情報の扱い
公開すると、git の履歴を含めて全体が誰にでも読める。一度でも秘密情報をコミットしていれば、それも公開される。
- **履歴の検査**: 公開の後(2026-09-28)に、gitleaks 8.30.1 で全履歴(`--all`。gitleaks の集計で 87 コミット)を検査した。
  - 検出は 7 件で、すべてテストのソースセットの中の、意図したダミーの値だった。
    - **JWT(3 件)**: 署名部が `signature` などの平文。
    - **PEM の断片(3 件)**: PKCS#8 の見出しの 39 バイトだけで、鍵として使えない。
    - **traceparent の不正な値(1 件)**: 変数名が `secretLike`。
  - 本物の秘密情報はなかった。
  - 7 件は、fingerprint(コミット・ファイル・ルール・行)で `.gitleaksignore` に登録した。パスや値の正規表現で許可すると、同じファイルに後から入った本物の秘密情報も見逃すため、検出の 1 件ごとに許可する。テストに新しいダミーの値を足して検出されたら、値がダミーであることを確かめてから、その fingerprint を追加する。
  - 検査は `gitleaks git`(git の履歴)で行い、`gitleaks dir` は使わない。`gitleaks dir` は、`.gitignore` の対象(`infra/local/.env` など)も読むため(CLAUDE.md §8)。
  - `infra/local/.env` と `infra/local/secrets/` は、一度もコミットされていない。履歴にある例のファイル(`.env.example`、現在は `env.example`)の値は、`__GENERATE__` と固定のユーザー名だけだった。
- **これまでの方針を続ける**
  - 秘密情報は `make env` が開発者ごとに乱数で作る。`.gitignore` の対象にする。
  - テストの秘密情報は、乱数で作るか、明らかなダミーにする。
  - コードは `SecretProvider` を経由して取得する(ADR-0008・ADR-0019 §6)。
- **CI**
  - リポジトリの Secrets は使わない(登録は 0 件)。
  - `pull_request_target` は使わない。fork からの PR は、書き込みの権限と Secrets のない `pull_request` で実行する。
  - 各 workflow の `permissions` は `contents: read` にした(ci の `changes` ジョブだけ、変更されたファイルの取得のために `pull-requests: read` を加える)。
  - `GITHUB_TOKEN` の既定の権限は read にした。
  - fork からの PR は、初めての貢献者の場合、実行の前に承認を求める(`first_time_contributors`)。
  - アクションは、コミットの SHA で固定する(既存の方針)。
- **脆弱性の報告**: `SECURITY.md` で、GitHub の Private vulnerability reporting を使って非公開で報告してもらう。Private vulnerability reporting は有効にした。

### 5. ライセンスはこの ADR では決めない
LICENSE を置いていないため、著作権法上、すべての権利が作者に留保される。GitHub の規約による閲覧と fork はできるが、利用・複製・改変・再配布はできない。README の末尾にそう書いた。ライセンスはリポジトリの所有者が決め、決めたら LICENSE を追加して README を直す。

## Alternatives Considered
- **private のまま、課金の上限を上げる**: CI は動くが、GitHub Free の private リポジトリでは Ruleset を使えない問題が残る。不採用。
- **private のまま、有料のプラン(Team など)にする**: Ruleset も使えるが、個人の参照実装に対して費用が見合わない。不採用。
- **ci・integration・macos・contract-check・infra をすべて必須チェックにする**: paths で起動しなかった workflow のチェックが報告されず、PR がマージできなくなる。すべてを常に起動するように変えると、Docker を使う infra の検査などが PR ごとに走り、時間がかかる。不採用。
- **承認の必要数を 1 にする**: 共同作業者がいないため、マージできなくなる。不採用。共同作業者を加えたら、この ADR を改訂して見直す。
- **pre-push フックを外し、Ruleset だけにする**: 誤った push が GitHub に届いてから拒否されることになる。手元で止めるほうが早く気づけるので、残す。

## Consequences(トレードオフ)
- コード・ADR・Issue・PR が誰にでも読める。設計の議論も公開される。
- 公開前の履歴も公開された。検査の結果、秘密情報はなかった。今後もコミットの前に秘密情報を入れないことが前提になる。CI での自動の検査(gitleaks など)は、この ADR では追加していない。
- ワークフローのコメントと ADR-0004 には、private リポジトリを前提にした記述が残っている。
  - `ci.yml`: キャッシュの提供元の選択の理由
  - `infra.yml`: ランナーのメモリ
  - ADR-0004: macOS のランナーの消費分数の倍率
  
  挙動には影響しないので、この ADR では変えていない。それぞれの workflow を次に変えるときに見直す。
- 管理者も main を直接書き換えられない。緊急時は、Ruleset を無効にする操作が要る。
- 必須チェックが `build` だけなので、integration などが失敗しても、GitHub はマージを止めない。実行されたジョブの結果を確かめるのは、運用(CLAUDE.md §6)である。

## 改訂履歴
- 2026-09-28: Ruleset のマージの方式を rebase だけにした(§2)。gitleaks の誤検知 7 件を `.gitleaksignore` に登録し、検査は git が追跡しているファイルだけを対象にすることにした(§4)。
