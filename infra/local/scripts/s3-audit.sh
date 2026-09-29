#!/usr/bin/env bash
# 監査のアンカー用のバケット(eiaf-audit)と、audit の資格情報の範囲を検査する(ADR-0017)。
# verify.sh が AWS CLI のコンテナ内で実行する。結果を 1 行ずつ「OK <内容>」「NG <内容>」で出力する。
# 環境変数: ADMIN_ACCESS_KEY / ADMIN_SECRET_KEY(管理者)、AUDIT_ACCESS_KEY / AUDIT_SECRET_KEY(audit)、
# VERIFY_ACCESS_KEY / VERIFY_SECRET_KEY(検査専用の eiaf-audit-verify)、RUN_ID、S3_ENDPOINT。
set -uo pipefail

aws configure set default.s3.addressing_style path
aws configure set default.region us-east-1
bucket=eiaf-audit
other="eiaf-verify-other-$RUN_ID"
key="verify/$RUN_ID.json"
work="$(mktemp -d)"
echo "{\"verify\":\"$RUN_ID\"}" >"$work/body.json"

as() { # as <admin|audit|verify> <s3api の引数...>
  local who="$1"; shift
  case "$who" in
    admin) AWS_ACCESS_KEY_ID="$ADMIN_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$ADMIN_SECRET_KEY" aws --endpoint-url "$S3_ENDPOINT" s3api "$@" ;;
    audit) AWS_ACCESS_KEY_ID="$AUDIT_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$AUDIT_SECRET_KEY" aws --endpoint-url "$S3_ENDPOINT" s3api "$@" ;;
    verify) AWS_ACCESS_KEY_ID="$VERIFY_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$VERIFY_SECRET_KEY" aws --endpoint-url "$S3_ENDPOINT" s3api "$@" ;;
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
# 資格情報はコマンドの文字列に埋め込まず、as 関数(環境変数)で渡す
lock_enabled() { [[ "$(as admin get-object-lock-configuration --bucket "$bucket" --query ObjectLockConfiguration.ObjectLockEnabled --output text)" == Enabled ]]; }
policy_applied() { as admin get-bucket-policy --bucket "$bucket" --output text | grep -q AuditIdentityCannotManageBucketOrDelete; }
allowed "バケット $bucket の Object Lock が有効" lock_enabled
allowed "バケットポリシーが設定されている(audit の管理操作を拒否する)" policy_applied

# --- audit の資格情報で必要な操作ができる ---
# 短縮の検査は、短縮後の期限がまだ先のうちに行う(過ぎた日時は権限の前に InvalidRequest で拒否されるため)
retain_until="$(date -u -d '+60 seconds' +%Y-%m-%dT%H:%M:%SZ)"
shorter="$(date -u -d '+30 seconds' +%Y-%m-%dT%H:%M:%SZ)"
version="$(as audit put-object --bucket "$bucket" --key "$key" --body "$work/body.json" \
  --object-lock-mode COMPLIANCE --object-lock-retain-until-date "$retain_until" --query VersionId --output text 2>/dev/null)"
if [[ -n "$version" && "$version" != None ]]; then ok "audit: COMPLIANCE の保持期限つきで put できる"; else ng "audit: COMPLIANCE の保持期限つきで put できる"; fi
allowed "audit: get-object できる" as audit get-object --bucket "$bucket" --key "$key" --version-id "$version" "$work/got.json"
allowed "audit: 全版の一覧(list-object-versions)を取得できる" as audit list-object-versions --bucket "$bucket" --prefix verify/
allowed "audit: 版の保持の設定(get-object-retention)を取得できる" as audit get-object-retention --bucket "$bucket" --key "$key" --version-id "$version"

# --- audit の資格情報でできないこと(Issue #6・ADR-0017 の項目 10) ---
denied "audit: バケットの Object Lock の設定を変更できない" as audit put-object-lock-configuration --bucket "$bucket" \
  --object-lock-configuration '{"ObjectLockEnabled":"Enabled","Rule":{"DefaultRetention":{"Mode":"GOVERNANCE","Days":1}}}'
denied "audit: バージョニングを変更できない" as audit put-bucket-versioning --bucket "$bucket" --versioning-configuration Status=Suspended
denied "audit: バケットポリシーを変更できない" as audit put-bucket-policy --bucket "$bucket" --policy '{"Version":"2012-10-17","Statement":[]}'
denied "audit: バケットポリシーを削除できない" as audit delete-bucket-policy --bucket "$bucket"
denied "audit: バケットを削除できない" as audit delete-bucket --bucket "$bucket"
denied "audit: 保持期限を短縮できない" as audit put-object-retention --bucket "$bucket" --key "$key" --version-id "$version" \
  --retention "{\"Mode\":\"COMPLIANCE\",\"RetainUntilDate\":\"$shorter\"}"
denied "audit: Legal Hold を変更できない" as audit put-object-legal-hold --bucket "$bucket" --key "$key" --version-id "$version" --legal-hold Status=OFF
denied "audit: 削除マーカーを作れない(版を指定しない delete)" as audit delete-object --bucket "$bucket" --key "$key"
denied "audit: 版を削除できない" as audit delete-object --bucket "$bucket" --key "$key" --version-id "$version"
denied "audit: バケットを作成できない" as audit create-bucket --bucket "$other-by-audit"
as admin create-bucket --bucket "$other" >/dev/null 2>&1
as admin put-object --bucket "$other" --key k.txt --body "$work/body.json" >/dev/null 2>&1
denied "audit: ほかのバケットを list できない" as audit list-objects-v2 --bucket "$other"
denied "audit: ほかのバケットに put できない" as audit put-object --bucket "$other" --key z.txt --body "$work/body.json"
denied "audit: ほかのバケットから get できない" as audit get-object --bucket "$other" --key k.txt "$work/other.txt"
denied "audit: ほかのバケットのオブジェクトを delete できない" as audit delete-object --bucket "$other" --key k.txt

# --- 検査専用の資格情報(eiaf-audit-verify)は読むだけ ---
allowed "verify: 全版の一覧(list-object-versions)を取得できる" as verify list-object-versions --bucket "$bucket" --prefix verify/
allowed "verify: get-object できる" as verify get-object --bucket "$bucket" --key "$key" --version-id "$version" "$work/got-verify.json"
allowed "verify: 版の保持の設定(get-object-retention)を取得できる" as verify get-object-retention --bucket "$bucket" --key "$key" --version-id "$version"
denied "verify: put できない" as verify put-object --bucket "$bucket" --key "verify/$RUN_ID-by-verify.json" --body "$work/body.json" \
  --object-lock-mode COMPLIANCE --object-lock-retain-until-date "$retain_until"
denied "verify: 削除マーカーを作れない" as verify delete-object --bucket "$bucket" --key "$key"
denied "verify: ほかのバケットを list できない" as verify list-objects-v2 --bucket "$other"

# --- COMPLIANCE は管理者の資格情報でも解除できない(ADR-0015 で持ち越した確認) ---
denied "admin: 保持期限内の版を削除できない(COMPLIANCE)" as admin delete-object --bucket "$bucket" --key "$key" --version-id "$version" --bypass-governance-retention
denied "admin: 保持期限を短縮できない(COMPLIANCE)" as admin put-object-retention --bucket "$bucket" --key "$key" --version-id "$version" \
  --bypass-governance-retention --retention "{\"Mode\":\"COMPLIANCE\",\"RetainUntilDate\":\"$shorter\"}"

# --- 後片付け(保持期限が切れるまで待ってから、管理者の資格情報で削除する) ---
now="$(date -u +%s)"
until_epoch="$(date -u -d "$retain_until" +%s)"
[[ "$until_epoch" -gt "$now" ]] && sleep $((until_epoch - now + 2))
as admin delete-object --bucket "$other" --key k.txt >/dev/null 2>&1
as admin delete-bucket --bucket "$other" >/dev/null 2>&1
if as admin delete-object --bucket "$bucket" --key "$key" --version-id "$version" >/dev/null 2>&1; then
  ok "admin: 保持期限の経過後は検査用の版を削除できる"
else
  ng "admin: 保持期限の経過後は検査用の版を削除できる"
fi
rm -rf "$work"
