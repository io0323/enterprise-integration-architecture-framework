#!/usr/bin/env bash
# ファイル授受のバケット(eiaf-file-exchange)と、batch-etl・file-exchange の資格情報の範囲を検査する(P09 ⑥。ADR-0032 §1)。
# verify.sh が AWS CLI のコンテナ内で実行する。結果を 1 行ずつ「OK <内容>」「NG <内容>」で出力する。
# 環境変数: ADMIN_ACCESS_KEY / ADMIN_SECRET_KEY(管理者)、WRITER_ACCESS_KEY / WRITER_SECRET_KEY(batch-etl の eiaf-file-batch-etl)、
# EXCHANGE_ACCESS_KEY / EXCHANGE_SECRET_KEY(file-exchange の eiaf-file-exchange)、RUN_ID、S3_ENDPOINT。
#
# 検査用のオブジェクトはマニフェストではない名前(.txt)にする(file-exchange の受け取りはマニフェストの到着で動くので、拾われない)。
set -uo pipefail

aws configure set default.s3.addressing_style path
aws configure set default.region us-east-1
bucket=eiaf-file-exchange
inbox="inbox/sales-daily"
key="$inbox/verify-$RUN_ID.txt"
work="$(mktemp -d)"
echo "verify $RUN_ID" >"$work/body.txt"

as() { # as <admin|writer|exchange> <s3api の引数...>
  local who="$1"; shift
  case "$who" in
    admin) AWS_ACCESS_KEY_ID="$ADMIN_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$ADMIN_SECRET_KEY" aws --endpoint-url "$S3_ENDPOINT" s3api "$@" ;;
    writer) AWS_ACCESS_KEY_ID="$WRITER_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$WRITER_SECRET_KEY" aws --endpoint-url "$S3_ENDPOINT" s3api "$@" ;;
    exchange) AWS_ACCESS_KEY_ID="$EXCHANGE_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$EXCHANGE_SECRET_KEY" aws --endpoint-url "$S3_ENDPOINT" s3api "$@" ;;
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
policy_applied() { as admin get-bucket-policy --bucket "$bucket" --output text | grep -q FileIdentitiesCannotManageBucket; }
allowed "バケット $bucket がある" as admin head-bucket --bucket "$bucket"
allowed "バケットポリシーが設定されている(ファイル授受の identity のバケットの管理操作を拒否する)" policy_applied

# --- 管理操作は、どちらの identity でも拒否する(承認の条件 1) ---
for who in writer exchange; do
  denied "$who: バケットポリシーを変更できない" as "$who" put-bucket-policy --bucket "$bucket" --policy '{"Version":"2012-10-17","Statement":[]}'
  denied "$who: バケットポリシーを削除できない" as "$who" delete-bucket-policy --bucket "$bucket"
  denied "$who: バケットを削除できない" as "$who" delete-bucket --bucket "$bucket"
  denied "$who: バージョニングを変更できない" as "$who" put-bucket-versioning --bucket "$bucket" --versioning-configuration Status=Enabled
  denied "$who: ライフサイクルを変更できない" as "$who" put-bucket-lifecycle-configuration --bucket "$bucket" \
    --lifecycle-configuration '{"Rules":[{"ID":"verify","Status":"Enabled","Filter":{"Prefix":"archive/"},"Expiration":{"Days":1}}]}'
  denied "$who: バケットを作成できない" as "$who" create-bucket --bucket "eiaf-verify-$who-$RUN_ID"
done

# --- batch-etl(eiaf-file-batch-etl)は受け口に書くだけ ---
allowed "writer: 受け口(inbox/sales-daily/)に put できる" as writer put-object --bucket "$bucket" --key "$key" --body "$work/body.txt"
denied "writer: 受け口の外(archive/)に put できない" as writer put-object --bucket "$bucket" --key "archive/sales-daily/verify-$RUN_ID.txt" --body "$work/body.txt"
denied "writer: ほかの受け口(inbox/other/)に put できない" as writer put-object --bucket "$bucket" --key "inbox/other/verify-$RUN_ID.txt" --body "$work/body.txt"
denied "writer: 読めない(get-object)" as writer get-object --bucket "$bucket" --key "$key" "$work/got-writer.txt"
denied "writer: 一覧できない" as writer list-objects-v2 --bucket "$bucket" --prefix "$inbox/"
denied "writer: 受け口のファイルを消せない(届いたファイルを受け手より先に消さない)" as writer delete-object --bucket "$bucket" --key "$key"

# --- file-exchange(eiaf-file-exchange)は検査と移動(読み書きと削除)ができる ---
allowed "exchange: 一覧できる" as exchange list-objects-v2 --bucket "$bucket" --prefix "$inbox/"
allowed "exchange: 読める" as exchange get-object --bucket "$bucket" --key "$key" "$work/got-exchange.txt"
allowed "exchange: archive/ にコピーできる" as exchange copy-object --bucket "$bucket" --key "archive/sales-daily/verify-$RUN_ID.txt" \
  --copy-source "$bucket/$key"
allowed "exchange: 受け口のファイルを消せる(移動の後)" as exchange delete-object --bucket "$bucket" --key "$key"
allowed "exchange: archive/ の検査用のファイルを消せる" as exchange delete-object --bucket "$bucket" --key "archive/sales-daily/verify-$RUN_ID.txt"
denied "exchange: ほかのバケット(eiaf-audit)を一覧できない" as exchange list-objects-v2 --bucket eiaf-audit

# --- 後片付け(残っていれば管理者が消す) ---
as admin delete-object --bucket "$bucket" --key "$key" >/dev/null 2>&1
as admin delete-object --bucket "$bucket" --key "archive/sales-daily/verify-$RUN_ID.txt" >/dev/null 2>&1
rm -rf "$work"
