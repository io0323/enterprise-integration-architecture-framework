#!/usr/bin/env bash
# S3 互換ストレージが、アプリ(P04a Audit / P09 File / P12 B2B)の使う S3 の機能を満たすかを検査する(ADR-0015)。
# verify.sh が AWS CLI のコンテナ内で実行する。結果を 1 行ずつ「OK <内容>」「NG <内容>」で出力する。
# 前提: path-style でアクセスする(ADR-0015)。環境変数 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / RUN_ID / S3_ENDPOINT / S3_PUBLIC_ENDPOINT。
set -uo pipefail

aws configure set default.s3.addressing_style path
aws configure set default.region us-east-1
s3api() { aws --endpoint-url "$S3_ENDPOINT" s3api "$@"; }
same() { [[ "$(sha256sum <"$1")" == "$(sha256sum <"$2")" ]]; }
ok() { echo "OK $1"; }
ng() { echo "NG $1${2:+ ($2)}"; }
step() { # step <内容> <コマンド...>
  local desc="$1"; shift
  local out
  if out="$("$@" 2>&1)"; then ok "$desc"; else ng "$desc" "$(tail -1 <<<"$out")"; fi
}

bucket="eiaf-verify-$RUN_ID"
locked="eiaf-verify-lock-$RUN_ID"
work="$(mktemp -d)"
echo "hello $RUN_ID" >"$work/v1.txt"
echo "second $RUN_ID" >"$work/v2.txt"

# --- put / get ---
step "バケットを作成できる" s3api create-bucket --bucket "$bucket"
step "put-object できる" s3api put-object --bucket "$bucket" --key docs/hello.txt --body "$work/v1.txt"
if s3api get-object --bucket "$bucket" --key docs/hello.txt "$work/got.txt" >/dev/null 2>&1 && same "$work/v1.txt" "$work/got.txt"; then
  ok "get-object で同じ内容を取得できる"
else
  ng "get-object で同じ内容を取得できる"
fi
step "一覧(list-objects-v2, prefix 指定)で取得できる" bash -c \
  "aws --endpoint-url '$S3_ENDPOINT' s3api list-objects-v2 --bucket '$bucket' --prefix docs/ --query 'Contents[].Key' --output text | grep -qx 'docs/hello.txt'"
step "copy-object(一時名 → 正式名の完了通知に使う)ができる" \
  s3api copy-object --bucket "$bucket" --copy-source "$bucket/docs/hello.txt" --key docs/hello.renamed.txt

# --- マルチパートアップロード(5 MiB + 1 MiB の 2 パート) ---
head -c $((5 * 1024 * 1024)) /dev/urandom >"$work/part1"
head -c $((1024 * 1024)) /dev/urandom >"$work/part2"
cat "$work/part1" "$work/part2" >"$work/whole"
upload_id="$(s3api create-multipart-upload --bucket "$bucket" --key big.bin --query UploadId --output text 2>/dev/null)"
etag1="$(s3api upload-part --bucket "$bucket" --key big.bin --part-number 1 --upload-id "$upload_id" --body "$work/part1" --query ETag --output text 2>/dev/null)"
etag2="$(s3api upload-part --bucket "$bucket" --key big.bin --part-number 2 --upload-id "$upload_id" --body "$work/part2" --query ETag --output text 2>/dev/null)"
parts="{\"Parts\":[{\"PartNumber\":1,\"ETag\":$etag1},{\"PartNumber\":2,\"ETag\":$etag2}]}"
if [[ -n "$upload_id" && -n "$etag1" && -n "$etag2" ]] &&
  s3api complete-multipart-upload --bucket "$bucket" --key big.bin --upload-id "$upload_id" --multipart-upload "$parts" >/dev/null 2>&1 &&
  s3api get-object --bucket "$bucket" --key big.bin "$work/big.got" >/dev/null 2>&1 && same "$work/whole" "$work/big.got"; then
  ok "マルチパートアップロード(2 パート・6 MiB)で完全な内容を取得できる"
else
  ng "マルチパートアップロード(2 パート・6 MiB)で完全な内容を取得できる" "upload_id=${upload_id:-なし}"
fi
step "マルチパートアップロードを中止(abort)できる" bash -c \
  "id=\$(aws --endpoint-url '$S3_ENDPOINT' s3api create-multipart-upload --bucket '$bucket' --key aborted.bin --query UploadId --output text) &&
   aws --endpoint-url '$S3_ENDPOINT' s3api abort-multipart-upload --bucket '$bucket' --key aborted.bin --upload-id \"\$id\""

# --- 署名付き URL(GET) ---
# 署名にはホスト名が含まれるため、取得する側から見えるエンドポイントで署名する。
# コンテナ内から取得できることと、ホスト向け(S3_PUBLIC_ENDPOINT)の URL が path-style になることを確かめる。
presigned="$(aws --endpoint-url "$S3_ENDPOINT" s3 presign "s3://$bucket/docs/hello.txt" --expires-in 300 2>/dev/null)"
if [[ -n "$presigned" ]] && curl -fsS -o "$work/presigned.txt" "$presigned" && same "$work/v1.txt" "$work/presigned.txt"; then
  ok "署名付き URL(GET)で認証情報なしに取得できる"
else
  ng "署名付き URL(GET)で認証情報なしに取得できる" "$presigned"
fi
tampered="${presigned/X-Amz-Signature=/X-Amz-Signature=0}"
code="$(curl -s -o /dev/null -w '%{http_code}' "$tampered")"
if [[ "$code" == 403 ]]; then ok "改ざんした署名付き URL を拒否する (HTTP 403)"; else ng "改ざんした署名付き URL を拒否する" "HTTP $code"; fi
public="$(aws --endpoint-url "$S3_PUBLIC_ENDPOINT" s3 presign "s3://$bucket/docs/hello.txt" --expires-in 300 2>/dev/null)"
if [[ "$public" == "$S3_PUBLIC_ENDPOINT/$bucket/docs/hello.txt?"* ]]; then
  ok "ホスト向けの署名付き URL が path-style($S3_PUBLIC_ENDPOINT/{bucket}/{key})になる"
else
  ng "ホスト向けの署名付き URL が path-style になる" "$public"
fi

# --- バージョニング ---
step "バージョニングを有効にできる" s3api put-bucket-versioning --bucket "$bucket" --versioning-configuration Status=Enabled
v1="$(s3api put-object --bucket "$bucket" --key versioned.txt --body "$work/v1.txt" --query VersionId --output text 2>/dev/null)"
v2="$(s3api put-object --bucket "$bucket" --key versioned.txt --body "$work/v2.txt" --query VersionId --output text 2>/dev/null)"
if [[ -n "$v1" && "$v1" != None && -n "$v2" && "$v1" != "$v2" ]] &&
  s3api get-object --bucket "$bucket" --key versioned.txt --version-id "$v1" "$work/old.txt" >/dev/null 2>&1 && same "$work/v1.txt" "$work/old.txt"; then
  ok "同じキーへの 2 回の put で版が分かれ、古い版を version-id で取得できる"
else
  ng "同じキーへの 2 回の put で版が分かれ、古い版を version-id で取得できる" "v1=$v1 v2=$v2"
fi

# --- Object Lock(Audit の日次アンカー: ADR-0008) ---
step "Object Lock 付きのバケットを作成できる" s3api create-bucket --bucket "$locked" --object-lock-enabled-for-bucket
step "Object Lock の設定が Enabled で、バージョニングも有効" bash -c \
  "[[ \$(aws --endpoint-url '$S3_ENDPOINT' s3api get-object-lock-configuration --bucket '$locked' --query ObjectLockConfiguration.ObjectLockEnabled --output text) == Enabled ]] &&
   [[ \$(aws --endpoint-url '$S3_ENDPOINT' s3api get-bucket-versioning --bucket '$locked' --query Status --output text) == Enabled ]]"
retain_until="$(date -u -d '+20 seconds' +%Y-%m-%dT%H:%M:%SZ)"
locked_version="$(s3api put-object --bucket "$locked" --key anchor.txt --body "$work/v1.txt" \
  --object-lock-mode COMPLIANCE --object-lock-retain-until-date "$retain_until" --query VersionId --output text 2>/dev/null)"
if [[ -n "$locked_version" && "$locked_version" != None ]]; then
  ok "COMPLIANCE モードの保持期限つきで put できる"
else
  ng "COMPLIANCE モードの保持期限つきで put できる"
fi
if s3api delete-object --bucket "$locked" --key anchor.txt --version-id "$locked_version" >/dev/null 2>&1; then
  ng "保持期限内の版は削除できない(COMPLIANCE)"
else
  ok "保持期限内の版は削除できない(COMPLIANCE)"
fi

# --- 後片付け(保持期限が切れるまで待ってから削除する) ---
cleanup_bucket() {
  local b="$1"
  aws --endpoint-url "$S3_ENDPOINT" s3api list-object-versions --bucket "$b" \
    --query '[Versions[].[Key,VersionId],DeleteMarkers[].[Key,VersionId]][]' --output text 2>/dev/null |
    while read -r key version; do
      [[ -n "$key" && "$key" != None ]] && s3api delete-object --bucket "$b" --key "$key" --version-id "$version" >/dev/null 2>&1
    done
  aws --endpoint-url "$S3_ENDPOINT" s3 rm "s3://$b" --recursive >/dev/null 2>&1
  s3api delete-bucket --bucket "$b" >/dev/null 2>&1
}
now="$(date -u +%s)"
until_epoch="$(date -u -d "$retain_until" +%s)"
[[ "$until_epoch" -gt "$now" ]] && sleep $((until_epoch - now + 2))
cleanup_bucket "$bucket"
cleanup_bucket "$locked"
if s3api head-bucket --bucket "$bucket" >/dev/null 2>&1 || s3api head-bucket --bucket "$locked" >/dev/null 2>&1; then
  ng "検査用のバケットを削除できる(保持期限の経過後)"
else
  ok "検査用のバケットを削除できる(保持期限の経過後)"
fi
rm -rf "$work"
