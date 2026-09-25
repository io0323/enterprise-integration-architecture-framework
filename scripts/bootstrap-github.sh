#!/usr/bin/env bash
# GitHub リポジトリ作成 + ラベル + マイルストーン + フェーズ Issue を一括作成する。
# 前提: gh CLI インストール済み & `gh auth login` 済み。リポジトリのルートで実行。
# 使い方: ./scripts/bootstrap-github.sh [--dry-run] [repo-name] [private|public]
#   --dry-run  GitHub への書き込みを行わず、実行予定の操作だけを表示する(参照系 API は呼ぶ)
# 何度実行しても安全(既存のラベル・マイルストーン・Issue は再作成しない)。
# CODEOWNERS の更新とブランチ保護は本スクリプトの対象外(PR で管理する)。
set -euo pipefail

DRY_RUN=false
POSITIONAL=()
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=true ;;
    -h|--help) sed -n '2,7p' "$0"; exit 0 ;;
    -*) echo "ERROR: unknown option: $arg" >&2; exit 2 ;;
    *) POSITIONAL+=("$arg") ;;
  esac
done
REPO_NAME="${POSITIONAL[0]:-enterprise-integration-architecture-framework}"
VISIBILITY="${POSITIONAL[1]:-private}"
API_SLEEP="${API_SLEEP:-1}"   # GitHub API 呼び出し間の待ち時間(秒)
MAX_ATTEMPTS=3

log() { echo "[bootstrap] $*"; }
plan() { echo "[dry-run] $*"; }

# 失敗時は待ち時間を延ばしながら最大 MAX_ATTEMPTS 回まで再試行する。成功時も API_SLEEP だけ待つ。
retry() {
  local attempt=1
  while true; do
    if "$@"; then
      sleep "$API_SLEEP"
      return 0
    fi
    if [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
      echo "ERROR: failed after $MAX_ATTEMPTS attempts: $*" >&2
      return 1
    fi
    log "retry $attempt/$MAX_ATTEMPTS failed, retrying: $1 $2 ..." >&2
    sleep $((API_SLEEP * attempt * 2))
    attempt=$((attempt + 1))
  done
}

OWNER="$(retry gh api user -q .login)"
REPO="$OWNER/$REPO_NAME"
log "target: $REPO (dry-run: $DRY_RUN)"

# 1) リポジトリ作成 & 初回 push(新規の場合のみ)
if [ ! -d .git ]; then
  if $DRY_RUN; then
    plan "git init -b main && git commit (initial scaffold)"
  else
    git init -b main
    git add . && git commit -m "chore: initial scaffold (docs, claude code config, github templates)"
  fi
fi
if ! gh repo view "$REPO" >/dev/null 2>&1; then
  if $DRY_RUN; then
    plan "gh repo create $REPO --$VISIBILITY --push"
  else
    retry gh repo create "$REPO" --"$VISIBILITY" --source=. --remote=origin --push \
      --description "Enterprise Integration Architecture Framework - Reference Implementation (Kotlin/KMP)"
  fi
fi
if grep -q '@YOUR_GITHUB_USER' .github/CODEOWNERS 2>/dev/null; then
  log "WARN: .github/CODEOWNERS still has @YOUR_GITHUB_USER. Replace it with @$OWNER via a PR."
fi

# 2) ラベル(--force で既存は上書き更新)
label() {
  if $DRY_RUN; then
    plan "label $1"
  else
    retry gh label create "$1" --color "$2" --description "$3" --repo "$REPO" --force >/dev/null
  fi
}
label "type:feature"      "1d76db" "機能実装"
label "type:integration"  "5319e7" "新規連携"
label "type:bug"          "d73a4a" "不具合"
label "type:adr"          "fbca04" "アーキテクチャ決定"
label "type:chore"        "cfd3d7" "ビルド・雑務"
for s in api event cdc batch file saas iot b2b security reliability observability governance platform infra; do
  label "area:$s" "0e8a16" "Framework area: $s"
done

# 3) マイルストーン + フェーズ Issue(docs/implementation/ROADMAP.md と一致させること)
# 存在確認は「API 失敗」と「存在しない」を区別するため、結果を標準出力で返し、呼び出し側で
# 通常の代入として受け取る(if / || の条件内で呼ぶと set -e が無効になり、失敗が「存在しない」に化けるため)。
milestone_titles() {
  retry gh api --paginate "repos/$REPO/milestones?state=all&per_page=100" --jq '.[].title'
}

issue_count() {
  retry gh issue list --repo "$REPO" --milestone "$1" --state all --json number -q 'length'
}

while IFS='|' read -r code title chapters area; do
  [ -z "$code" ] && continue

  titles="$(milestone_titles)"
  if grep -qxF "$code" <<<"$titles"; then
    log "milestone $code: exists"
    has_milestone=true
  elif $DRY_RUN; then
    plan "create milestone $code ($title)"
    has_milestone=false
  else
    retry gh api "repos/$REPO/milestones" -f title="$code" -f description="$title" >/dev/null
    titles="$(milestone_titles)"
    if ! grep -qxF "$code" <<<"$titles"; then
      echo "ERROR: milestone $code was not created" >&2
      exit 1
    fi
    log "milestone $code: created"
    has_milestone=true
  fi

  count=0
  if $has_milestone; then
    count="$(issue_count "$code")"
  fi
  if [ "$count" -gt 0 ]; then
    log "issue [$code]: exists"
  elif $DRY_RUN; then
    plan "create issue [$code] $title (labels: type:feature, area:$area; chapters: $chapters)"
  else
    retry gh issue create --repo "$REPO" --milestone "$code" --label "type:feature" --label "area:$area" \
      --title "[$code] $title" \
      --body "ROADMAP: docs/implementation/ROADMAP.md の **$code** 節を参照。
Framework 参照章: $chapters

- [ ] 実装計画の承認
- [ ] 実装
- [ ] DoD 全項目の検証
- [ ] PR マージ" >/dev/null
    log "issue [$code]: created"
  fi
done <<'PHASES'
P00|Repository Bootstrap|16, 19|governance
P01|Shared Kernel & Canonical Model (KMP)|15|platform
P02|Contracts & Governance CI|5, 6, 15, 16|governance
P03|Local Infrastructure|2, 17|infra
P04a|Platform: Observability, Security & Audit|12, 14|observability
P04b|Platform: Resilience|13|reliability
P05|API Integration: order-service|5, 12|api
P06|Outbox & CDC|8|cdc
P07|Event Integration + Saga|4, 6, 13|event
P08|Batch & ELT/ETL|7|batch
P09|File Integration|9|file
P10|SaaS / Webhook / iPaaS-like Flow|10|saas
P11|IoT (MQTT) & KMP SDK|6, 17.3|iot
P12|B2B / EDI Gateway|11|b2b
P13|gRPC & GraphQL BFF|3, 5, 12|api
P14|Resilience, E2E & SLO|13, 14, 18|reliability
PHASES

log "done: https://github.com/$REPO"
