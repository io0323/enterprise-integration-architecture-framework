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
