#!/usr/bin/env bash
# infra/local/.env を生成する(既にあれば何もしない)。ADR-0016 §6。
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
env_file="$here/.env"
secrets_dir="$here/secrets"

random_value() {
  # 英数字 32 文字(URL・YAML・JDBC にそのまま埋め込めるよう記号を使わない)
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32 || true
}

if [[ -f "$env_file" ]]; then
  # 既存の .env に無い変数だけを追記する(フェーズが進んで変数が増えたとき用)
  added=0
  while IFS= read -r line; do
    [[ "$line" =~ ^([A-Z0-9_]+)=(.*)$ ]] || continue
    key="${BASH_REMATCH[1]}"; value="${BASH_REMATCH[2]}"
    grep -q "^${key}=" "$env_file" && continue
    [[ "$value" == "__GENERATE__" ]] && value="$(random_value)"
    printf '%s=%s\n' "$key" "$value" >>"$env_file"
    added=$((added + 1))
  done <"$here/env.example"
  echo "infra/local/.env は既にあります(不足していた変数を ${added} 件追記)"
else
  umask 077
  while IFS= read -r line; do
    if [[ "$line" =~ ^([A-Z0-9_]+)=__GENERATE__$ ]]; then
      printf '%s=%s\n' "${BASH_REMATCH[1]}" "$(random_value)"
    else
      printf '%s\n' "$line"
    fi
  done <"$here/env.example" >"$env_file"
  echo "infra/local/.env を生成しました"
fi


# SFTP のクライアント鍵(公開鍵をサーバに登録し、秘密鍵は verify とアプリが使う)。**/secrets/ は .gitignore 済み。
secrets_dir="$here/secrets"
mkdir -p "$secrets_dir"
for name in sftp-file sftp-b2b; do
  if [[ ! -f "$secrets_dir/$name" ]]; then
    ssh-keygen -q -t ed25519 -N '' -C "eiaf-$name" -f "$secrets_dir/$name"
    echo "infra/local/secrets/$name を生成しました"
  fi
done
chmod 644 "$secrets_dir"/*.pub

# 取引先の代わりの SFTP(sftp-b2b)のホスト鍵(P09 ⑥。ADR-0032 §8)。サーバの起動時のフック(sftp/20-host-key.sh)が使い、
# file-exchange と make verify はこの公開鍵を known_hosts に固定して接続する(初めての接続で鍵を覚える方式にしない)。
# known_hosts の行は、コンテナの間の宛先([sftp-b2b]:2222)とホストからの宛先([127.0.0.1]:19223)の両方に同じ鍵を書く。
# 作り直すときは secrets/sftp-b2b-host-key* と secrets/sftp-b2b.known_hosts を消して make env を実行する(docs/runbooks/file-exchange.md)
host_key="$secrets_dir/sftp-b2b-host-key"
if [[ ! -f "$host_key" ]]; then
  ssh-keygen -q -t ed25519 -N '' -C "eiaf-sftp-b2b-host" -f "$host_key"
  rm -f "$secrets_dir/sftp-b2b.known_hosts"
  echo "infra/local/secrets/sftp-b2b-host-key を生成しました"
fi
if [[ ! -f "$secrets_dir/sftp-b2b.known_hosts" ]]; then
  read -r key_type key_body _ <"$host_key.pub"
  printf '[sftp-b2b]:2222,[127.0.0.1]:19223 %s %s\n' "$key_type" "$key_body" >"$secrets_dir/sftp-b2b.known_hosts"
fi
chmod 644 "$host_key.pub" "$secrets_dir/sftp-b2b.known_hosts"

# CRM の Webhook の署名の鍵(saas profile。P10 ⑥。ADR-0035 §2)。Standard Webhooks の形(whsec_ に続く base64)。
# 送り手(saas-mock)と受け口(webhook-receiver)は本来は別の置き場なので、ファイルを分ける。最初は同じ値にする。
# - sender-current / sender-next: saas-mock が送るたびに読む(next があれば新旧の両方で署名する)
# - receiver-current / receiver-previous: webhook-receiver が要求のたびに読む(previous があれば両方を受け付ける)
# 差し替えの手順は docs/runbooks/webhook-secret-rotation.md(ファイルを置き換えるだけで、再起動は要らない)
webhook_dir="$secrets_dir/crm-webhook"
mkdir -p "$webhook_dir"
if [[ ! -f "$webhook_dir/receiver-current" ]]; then
  key="whsec_$(head -c 24 /dev/urandom | base64 | tr -d '\n')"
  (umask 077 && printf '%s\n' "$key" >"$webhook_dir/sender-current" && printf '%s\n' "$key" >"$webhook_dir/receiver-current")
  echo "infra/local/secrets/crm-webhook/(Webhook の署名の鍵)を生成しました"
fi
