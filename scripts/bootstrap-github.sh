#!/usr/bin/env bash
# GitHub リポジトリ作成 + ラベル + マイルストーン + フェーズ Issue を一括作成する。
# 前提: gh CLI インストール済み & `gh auth login` 済み。リポジトリのルートで実行。
# 使い方: ./scripts/bootstrap-github.sh <repo-name> [private|public]
set -euo pipefail
REPO_NAME="${1:-enterprise-integration-architecture-framework}"
VISIBILITY="${2:-private}"
OWNER="$(gh api user -q .login)"
REPO="$OWNER/$REPO_NAME"

# 1) リポジトリ作成 & 初回 push
if [ ! -d .git ]; then
  git init -b main
  git add . && git commit -m "chore: initial scaffold (docs, claude code config, github templates)"
fi
if ! gh repo view "$REPO" >/dev/null 2>&1; then
  gh repo create "$REPO" --"$VISIBILITY" --source=. --remote=origin --push \
    --description "Enterprise Integration Architecture Framework - Reference Implementation (Kotlin/KMP)"
fi
sed -i.bak "s/@YOUR_GITHUB_USER/@$OWNER/g" .github/CODEOWNERS && rm -f .github/CODEOWNERS.bak

# 2) ラベル
label() { gh label create "$1" --color "$2" --description "$3" --repo "$REPO" --force >/dev/null; }
label "type:feature"      "1d76db" "機能実装"
label "type:integration"  "5319e7" "新規連携"
label "type:bug"          "d73a4a" "不具合"
label "type:adr"          "fbca04" "アーキテクチャ決定"
label "type:chore"        "cfd3d7" "ビルド・雑務"
for s in api event cdc batch file saas iot b2b security reliability observability governance platform infra; do
  label "area:$s" "0e8a16" "Framework area: $s"
done

# 3) マイルストーン + フェーズ Issue
while IFS='|' read -r code title chapters area; do
  [ -z "$code" ] && continue
  gh api "repos/$REPO/milestones" -f title="$code" -f description="$title" >/dev/null 2>&1 || true
  if ! gh issue list --repo "$REPO" --milestone "$code" --state all --json number -q '.[0].number' | grep -q .; then
    gh issue create --repo "$REPO" --milestone "$code" --label "type:feature" --label "area:$area" \
      --title "[$code] $title" \
      --body "ROADMAP: docs/implementation/ROADMAP.md の **$code** 節を参照。
Framework 参照章: $chapters

- [ ] 実装計画の承認
- [ ] 実装
- [ ] DoD 全項目の検証
- [ ] PR マージ" >/dev/null
  fi
done <<'PHASES'
P00|Repository Bootstrap|16, 19|governance
P01|Shared Kernel & Canonical Model (KMP)|15|platform
P02|Contracts & Governance CI|5, 6, 15, 16|governance
P03|Local Infrastructure|2, 17|infra
P04|Platform Libraries|12, 13, 14|platform
P05|API Integration: order-service|5|api
P06|Outbox & CDC|8|cdc
P07|Event Integration + Saga|4, 6, 13|event
P08|Batch & ETL|7|batch
P09|File Integration|9|file
P10|SaaS / Webhook / iPaaS-like Flow|10|saas
P11|IoT (MQTT) & KMP SDK|6, 17.3|iot
P12|B2B / EDI Gateway|11|b2b
P13|gRPC & GraphQL BFF|3, 5|api
P14|Resilience, E2E & SLO|13, 14, 18|reliability
PHASES

# 4) main ブランチ保護(private リポジトリでは有料プランが必要。失敗しても続行)
gh api -X PUT "repos/$REPO/branches/main/protection" --input - >/dev/null 2>&1 <<'JSON' || echo "WARN: branch protection skipped (plan/visibility)"
{
  "required_status_checks": { "strict": true, "contexts": ["build"] },
  "enforce_admins": false,
  "required_pull_request_reviews": { "required_approving_review_count": 0 },
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
JSON

git add .github/CODEOWNERS && git commit -m "chore: set CODEOWNERS" >/dev/null 2>&1 && git push >/dev/null 2>&1 || true
echo "Done: https://github.com/$REPO"
