#!/usr/bin/env bash
# B2B の原本のバケット(eiaf-b2b-originals)を作り、バケットポリシーを設定する(P12 ⑥b。ADR-0040 §5)。
# init-audit-bucket.sh(seaweedfs-init)が、管理者の資格情報で make up のたびに呼ぶ。何度実行しても結果は同じ。
# - Object Lock を有効にして作る(バージョニングも有効になる)。既定の保持設定は付けず、b2b-gateway が put のたびに COMPLIANCE と保持期限を
#   明示する(監査のアンカーのバケットと同じ。ADR-0017 §5)
# - ポリシー(b2b-bucket-policy.json)で、b2b-gateway の identity の削除・保持の設定の変更・Legal Hold・バケットの管理操作を拒否する
set -euo pipefail

bucket=eiaf-b2b-originals
s3api() { aws --endpoint-url "$S3_ENDPOINT" s3api "$@"; }

if s3api head-bucket --bucket "$bucket" >/dev/null 2>&1; then
  echo "$bucket は既にあります"
else
  s3api create-bucket --bucket "$bucket" --object-lock-enabled-for-bucket >/dev/null
  echo "$bucket を作成しました"
fi

lock="$(s3api get-object-lock-configuration --bucket "$bucket" --query ObjectLockConfiguration.ObjectLockEnabled --output text)"
if [[ "$lock" != Enabled ]]; then
  echo "$bucket の Object Lock が有効ではありません($lock)。make clean で作り直してください" >&2
  exit 1
fi
# ポリシーは毎回設定し直す(ファイルの変更を反映し、ストレージ側で失われていても戻す)
s3api put-bucket-policy --bucket "$bucket" --policy file:///init/b2b-bucket-policy.json
echo "$bucket のバケットポリシーを設定しました"
