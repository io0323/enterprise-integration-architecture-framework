# ADR-0002: モノレポ構成
- Status: Accepted
- Date: 2026-09-25
- Framework 参照章: 16

## Decision
contracts / shared / platform / services / tools / infra / docs を単一リポジトリで管理する。
## Rationale
契約と実装の同時変更・CI 互換性検査・Claude Code による全体把握が容易。
## Consequences
CI はパス別フィルタと Gradle ビルドキャッシュで時間を抑える。将来、組織拡大時は contracts を分離可能な構造を保つ。
