#!/usr/bin/env bash
# 監査記録の改竄の検査(make audit-verify)が OK で、かつアンカーが 1 版以上あることを確かめる(ADR-0017 §5・§6)。
# アンカーを定期的に保存するサービス(order)の検証(make verify PROFILE=order・make e2e)で使う。
# audit-verify.sh は、アンカーがなくても(注意を出して)終了コード 0 を返すため、ここで版の数も見る。
# 終了コード: 0 = OK かつアンカーあり / 1 = それ以外(audit-verify.sh の出力をそのまま表示する)
set -uo pipefail

service="${1:?サービス名を指定してください(例: order)}"
here="$(cd "$(dirname "$0")" && pwd)"

output="$("$here/audit-verify.sh" "$service" 2>&1)"
status=$?
printf '%s\n' "$output"
if [[ "$status" -ne 0 ]]; then
  echo "audit-anchored: make audit-verify SERVICE=$service の終了コードが $status です" >&2
  exit 1
fi
if ! grep -q -E 'anchor_versions=[1-9]' <<<"$output"; then
  echo "audit-anchored: $service のアンカーがありません(order-service のアンカーの保存を確かめてください)" >&2
  exit 1
fi
