---
description: ROADMAP のフェーズを計画→実装→検証→PR まで進める
argument-hint: <phase 例: P05>
---
フェーズ **$ARGUMENTS** を実装する。

1. CLAUDE.md、docs/implementation/ROADMAP.md の $ARGUMENTS 節、関連する docs/architecture/EIA-Framework.md の章、MODULE_DESIGN.md を読む。
2. `gh issue list --milestone "$ARGUMENTS"` で対象 Issue を確認する。
3. 実装計画(作成/変更するモジュール・ファイル、契約変更、テスト、ADR 要否、リスク)を提示し、**私の承認を待つ**。
4. 承認後、`feat/<phase小文字>-<slug>` ブランチで実装。Contract First(契約→テスト→実装)の順に進める。
5. `./gradlew build` と必要に応じ `./gradlew integrationTest`、契約変更時は contract-check を実行し、全て成功させる。
6. DoD を 1 項目ずつ満たしたか証拠(テスト名・コマンド出力)付きで報告する。
7. Conventional Commits でコミットし、PR 本文案(.github/PULL_REQUEST_TEMPLATE.md 準拠、`Closes #<issue>`)を提示する。push と PR 作成は私の確認後に行う。
