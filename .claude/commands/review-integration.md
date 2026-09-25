---
description: 現在の差分を Framework のレビュー観点でチェックする
---
`git diff main...HEAD` を対象に、integration-architect-reviewer サブエージェントを使ってレビューする。
観点: 方式選定根拠 / 契約と互換性 / 命名 / セキュリティ / 信頼性(必須セット) / 可観測性 / Clean Architecture 依存方向 / テスト / カタログ登録 / ADR 要否。
結果は「Blocker / Major / Minor / Good」で分類し、該当ファイル:行 と Framework 章番号を付けて報告する。
