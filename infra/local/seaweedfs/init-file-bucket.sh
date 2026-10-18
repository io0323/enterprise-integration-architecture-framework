#!/usr/bin/env bash
# ファイル授受のバケット(eiaf-file-exchange)を作り、バケットポリシーを設定する(P09 ⑥。ADR-0032 §1)。
# init-audit-bucket.sh(seaweedfs-init)が、管理者の資格情報で make up のたびに呼ぶ。何度実行しても結果は同じ。
# - Object Lock は使わない(受け口のファイルは移動で消す。保存期間はライフサイクルで決める。#22)
# - ポリシー(file-bucket-policy.json)で、batch-etl と file-exchange の identity のバケットの管理操作(ポリシー・バージョニング・
#   削除・ライフサイクルの変更)を拒否する。SeaweedFS の Write にはバケットの管理操作が含まれうるため(ADR-0017 §7 と同じ)。
#   batch-etl(受け口に書くだけ)はファイルも消せない
set -euo pipefail

bucket=eiaf-file-exchange
s3api() { aws --endpoint-url "$S3_ENDPOINT" s3api "$@"; }

if s3api head-bucket --bucket "$bucket" >/dev/null 2>&1; then
  echo "$bucket は既にあります"
else
  s3api create-bucket --bucket "$bucket" >/dev/null
  echo "$bucket を作成しました"
fi
# ポリシーは毎回設定し直す(ファイルの変更を反映し、ストレージ側で失われていても戻す)
s3api put-bucket-policy --bucket "$bucket" --policy file:///init/file-bucket-policy.json
echo "$bucket のバケットポリシーを設定しました"
