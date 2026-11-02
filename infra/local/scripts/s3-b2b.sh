#!/usr/bin/env bash
# B2B の原本のバケット(eiaf-b2b-originals)と、b2b-gateway の 2 つの資格情報の範囲を検査する(P12 ⑥b。ADR-0040 §5・ADR-0017 §7。Issue #6)。
# verify.sh が AWS CLI のコンテナ内で実行する。結果を 1 行ずつ「OK <内容>」「NG <内容>」で出力する。
# 環境変数: ADMIN_ACCESS_KEY / ADMIN_SECRET_KEY(管理者)、B2B_ACCESS_KEY / B2B_SECRET_KEY(原本を書く eiaf-b2b-gateway)、
# ANCHOR_ACCESS_KEY / ANCHOR_SECRET_KEY(監査のアンカーを書く eiaf-audit-b2b-gateway)、RUN_ID、S3_ENDPOINT。
set -uo pipefail

aws configure set default.s3.addressing_style path
aws configure set default.region us-east-1
bucket=eiaf-b2b-originals
key="originals/verify/received/verify-$RUN_ID.pgp"
anchor="anchors/b2b-gateway/verify-$RUN_ID.json"
work="$(mktemp -d)"
echo "verify $RUN_ID" >"$work/body.txt"

as() { # as <admin|b2b|anchor> <s3api の引数...>
  local who="$1"; shift
  case "$who" in
    admin) AWS_ACCESS_KEY_ID="$ADMIN_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$ADMIN_SECRET_KEY" aws --endpoint-url "$S3_ENDPOINT" s3api "$@" ;;
    b2b) AWS_ACCESS_KEY_ID="$B2B_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$B2B_SECRET_KEY" aws --endpoint-url "$S3_ENDPOINT" s3api "$@" ;;
    anchor) AWS_ACCESS_KEY_ID="$ANCHOR_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$ANCHOR_SECRET_KEY" aws --endpoint-url "$S3_ENDPOINT" s3api "$@" ;;
  esac
}
ok() { echo "OK $1"; }
ng() { echo "NG $1${2:+ ($2)}"; }
allowed() { # allowed <内容> <コマンド...>: 成功すれば OK
  local desc="$1"; shift
  local out
  if out="$("$@" 2>&1)"; then ok "$desc"; else ng "$desc" "$(tail -1 <<<"$out")"; fi
}
denied() { # denied <内容> <コマンド...>: AccessDenied で失敗すれば OK(ほかの理由の失敗は NG。権限で拒否されたことを確かめるため)
  local desc="$1"; shift
  local out
  if out="$("$@" 2>&1)"; then
    ng "$desc" "許可されてしまう"
  elif grep -q "AccessDenied" <<<"$out"; then
    ok "$desc"
  else
    ng "$desc" "AccessDenied 以外で失敗: $(tail -1 <<<"$out")"
  fi
}

# --- バケットの設定 ---
lock_enabled() { [[ "$(as admin get-object-lock-configuration --bucket "$bucket" --query ObjectLockConfiguration.ObjectLockEnabled --output text)" == Enabled ]]; }
policy_applied() { as admin get-bucket-policy --bucket "$bucket" --output text | grep -q B2bGatewayCannotManageBucketOrDelete; }
allowed "バケット $bucket の Object Lock が有効" lock_enabled
allowed "バケットポリシーが設定されている(b2b-gateway の削除・Legal Hold・バケットの管理操作を拒否する)" policy_applied

# --- 原本を書く資格情報(eiaf-b2b-gateway)で必要な操作ができる ---
retain_until="$(date -u -d '+60 seconds' +%Y-%m-%dT%H:%M:%SZ)"
version="$(as b2b put-object --bucket "$bucket" --key "$key" --body "$work/body.txt" \
  --object-lock-mode COMPLIANCE --object-lock-retain-until-date "$retain_until" --query VersionId --output text 2>/dev/null)"
if [[ -n "$version" && "$version" != None ]]; then ok "b2b: originals/ に COMPLIANCE の保持期限つきで put できる"; else ng "b2b: originals/ に COMPLIANCE の保持期限つきで put できる"; fi
allowed "b2b: 版を指定して get できる" as b2b get-object --bucket "$bucket" --key "$key" --version-id "$version" "$work/got.txt"
allowed "b2b: 一覧を取得できる" as b2b list-objects-v2 --bucket "$bucket" --prefix originals/verify/

# --- 原本を書く資格情報でできないこと ---
denied "b2b: originals/・readable/ の外に put できない" as b2b put-object --bucket "$bucket" --key "other/verify-$RUN_ID.txt" --body "$work/body.txt" \
  --object-lock-mode COMPLIANCE --object-lock-retain-until-date "$retain_until"
denied "b2b: 削除マーカーを作れない(版を指定しない delete)" as b2b delete-object --bucket "$bucket" --key "$key"
denied "b2b: 版を削除できない" as b2b delete-object --bucket "$bucket" --key "$key" --version-id "$version"
denied "b2b: Legal Hold を変更できない" as b2b put-object-legal-hold --bucket "$bucket" --key "$key" --version-id "$version" --legal-hold Status=OFF
denied "b2b: バケットの Object Lock の設定を変更できない" as b2b put-object-lock-configuration --bucket "$bucket" \
  --object-lock-configuration '{"ObjectLockEnabled":"Enabled","Rule":{"DefaultRetention":{"Mode":"GOVERNANCE","Days":1}}}'
denied "b2b: バージョニングを変更できない" as b2b put-bucket-versioning --bucket "$bucket" --versioning-configuration Status=Suspended
denied "b2b: バケットポリシーを変更できない" as b2b put-bucket-policy --bucket "$bucket" --policy '{"Version":"2012-10-17","Statement":[]}'
denied "b2b: バケットポリシーを削除できない" as b2b delete-bucket-policy --bucket "$bucket"
denied "b2b: バケットを削除できない" as b2b delete-bucket --bucket "$bucket"
denied "b2b: バケットを作成できない" as b2b create-bucket --bucket "eiaf-verify-b2b-$RUN_ID"
denied "b2b: 監査のアンカーのバケットに書けない" as b2b put-object --bucket eiaf-audit --key "anchors/b2b-gateway/verify-$RUN_ID-by-b2b.json" \
  --body "$work/body.txt" --object-lock-mode COMPLIANCE --object-lock-retain-until-date "$retain_until"
denied "b2b: ファイル授受のバケットを読めない" as b2b list-objects-v2 --bucket eiaf-file-exchange

# --- 監査のアンカーを書く資格情報(eiaf-audit-b2b-gateway) ---
anchor_version="$(as anchor put-object --bucket eiaf-audit --key "$anchor" --body "$work/body.txt" \
  --object-lock-mode COMPLIANCE --object-lock-retain-until-date "$retain_until" --query VersionId --output text 2>/dev/null)"
if [[ -n "$anchor_version" && "$anchor_version" != None ]]; then ok "anchor: anchors/b2b-gateway/ に put できる"; else ng "anchor: anchors/b2b-gateway/ に put できる"; fi
denied "anchor: ほかのサービスのプレフィックス(anchors/order/)に put できない" as anchor put-object --bucket eiaf-audit \
  --key "anchors/order/verify-$RUN_ID-by-b2b.json" --body "$work/body.txt" --object-lock-mode COMPLIANCE --object-lock-retain-until-date "$retain_until"
denied "anchor: 版を削除できない" as anchor delete-object --bucket eiaf-audit --key "$anchor" --version-id "$anchor_version"
denied "anchor: Legal Hold を変更できない" as anchor put-object-legal-hold --bucket eiaf-audit --key "$anchor" --version-id "$anchor_version" \
  --legal-hold Status=OFF
denied "anchor: 原本のバケットに書けない" as anchor put-object --bucket "$bucket" --key "originals/verify/by-anchor-$RUN_ID.pgp" --body "$work/body.txt" \
  --object-lock-mode COMPLIANCE --object-lock-retain-until-date "$retain_until"

# --- 後片付け(保持期限が切れるまで待ってから、管理者の資格情報で削除する) ---
now="$(date -u +%s)"
until_epoch="$(date -u -d "$retain_until" +%s)"
[[ "$until_epoch" -gt "$now" ]] && sleep $((until_epoch - now + 2))
if as admin delete-object --bucket "$bucket" --key "$key" --version-id "$version" >/dev/null 2>&1 &&
  as admin delete-object --bucket eiaf-audit --key "$anchor" --version-id "$anchor_version" >/dev/null 2>&1; then
  ok "admin: 保持期限の経過後は検査用の版を削除できる"
else
  ng "admin: 保持期限の経過後は検査用の版を削除できる"
fi
rm -rf "$work"
