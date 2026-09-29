#!/usr/bin/env bash
# 監査のアンカー用のバケット(eiaf-audit)を作り、audit の identity の管理操作を拒否するバケットポリシーを設定する(ADR-0017)。
# seaweedfs-init コンテナ(AWS CLI のイメージ)が、管理者の資格情報で `make up` のたびに実行する。何度実行しても結果は同じ。
# 初期化を終えたら完了のファイルを作って待機する(終了しない)。`make up`(`up --wait`)は、ヘルスチェックでこのファイルを見て完了を待つ。
# 終了させない理由: Compose v2.38(CI)の `up --wait` は、終了コード 0 で終わったコンテナも失敗として扱うため(ADR-0017 の Consequences)。
set -euo pipefail

ready=/tmp/initialized
rm -f "$ready"

bucket=eiaf-audit
aws configure set default.s3.addressing_style path
aws configure set default.region us-east-1
s3api() { aws --endpoint-url "$S3_ENDPOINT" s3api "$@"; }

if s3api head-bucket --bucket "$bucket" >/dev/null 2>&1; then
  echo "$bucket は既にあります"
else
  # Object Lock を有効にして作る(バージョニングも有効になる)。既定の保持設定は付けず、アプリが put のたびに COMPLIANCE と保持期限を明示する
  s3api create-bucket --bucket "$bucket" --object-lock-enabled-for-bucket >/dev/null
  echo "$bucket を作成しました"
fi

lock="$(s3api get-object-lock-configuration --bucket "$bucket" --query ObjectLockConfiguration.ObjectLockEnabled --output text)"
if [[ "$lock" != Enabled ]]; then
  echo "$bucket の Object Lock が有効ではありません($lock)。make clean で作り直してください" >&2
  exit 1
fi

# ポリシーは毎回設定し直す(ファイルの変更を反映し、ストレージ側で失われていても戻す)
s3api put-bucket-policy --bucket "$bucket" --policy file:///init/audit-bucket-policy.json
echo "$bucket のバケットポリシーを設定しました"

touch "$ready"
# SIGTERM(make down)ですぐに止まるよう、sleep をバックグラウンドで待つ
trap 'exit 0' TERM INT
while true; do sleep 3600 & wait $!; done
