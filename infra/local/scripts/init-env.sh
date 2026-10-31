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
    # 取引先の鍵を作り直したら、台帳の鍵の期日も作り直す(下の「取引先の台帳」)
    [[ "$name" == sftp-b2b ]] && rm -f "$secrets_dir/partner01-keys.valid-until"
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
  rm -f "$secrets_dir/sftp-b2b.known_hosts" "$secrets_dir/partner01-keys.valid-until"
  echo "infra/local/secrets/sftp-b2b-host-key を生成しました"
fi
if [[ ! -f "$secrets_dir/sftp-b2b.known_hosts" ]]; then
  read -r key_type key_body _ <"$host_key.pub"
  printf '[sftp-b2b]:2222,[127.0.0.1]:19223 %s %s\n' "$key_type" "$key_body" >"$secrets_dir/sftp-b2b.known_hosts"
fi
chmod 644 "$host_key.pub" "$secrets_dir/sftp-b2b.known_hosts"

# 取引先の台帳(b2b/partners.json。P12 ③。ADR-0040 §3)。鍵とホスト鍵の期日(TPA で合意した差し替えの期日)は、コミットした台帳
# (b2b/partners.template.json の @KEYS_VALID_UNTIL@)に書かず、鍵を作った日から 1 年後をここで決めて書き込む。固定の期日を
# コミットすると、日が経つだけで鍵の期限のアラートが出始め、verify と e2e が失敗するため。期日は鍵を作り直すまで変えない
# (secrets/partner01-keys.valid-until に残す)。台帳は毎回テンプレートから作り直す(b2b/partners.json は .gitignore 済み)。
valid_file="$secrets_dir/partner01-keys.valid-until"
if [[ ! -f "$valid_file" ]]; then
  python3 -c 'import datetime; print((datetime.date.today() + datetime.timedelta(days=365)).isoformat())' >"$valid_file"
  echo "infra/local/secrets/partner01-keys.valid-until(取引先の鍵の期日)を生成しました: $(cat "$valid_file")"
fi
sed "s/@KEYS_VALID_UNTIL@/$(cat "$valid_file")/g" "$here/b2b/partners.template.json" >"$here/b2b/partners.json"

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

# MQTT のデバイスごとのパスワード(iot profile。P11 ⑥。ADR-0039 §3)。mosquitto/devices.txt の各デバイスについて、
# まだなければ作る(1 行に「{tenant}/{deviceId} パスワード」)。Mosquitto は起動のたびにここからパスワードと ACL を作り直す。
# デバイスのシミュレータ(make e2e・手で動かすとき)は、ここからそのデバイスのパスワードを読んで環境変数で渡す。
mqtt_dir="$secrets_dir/mqtt"
mqtt_passwords="$mqtt_dir/device-passwords"
mkdir -p "$mqtt_dir"
(umask 077 && touch "$mqtt_passwords")
mqtt_added=0
while IFS= read -r device; do
  [[ "$device" =~ ^[[:space:]]*(#|$) ]] && continue
  if [[ ! "$device" =~ ^[a-z0-9][a-z0-9-]*/[a-z0-9][a-z0-9-]*$ ]]; then
    echo "mosquitto/devices.txt: {tenant}/{deviceId}(小文字の英数字と -)ではありません: $device" >&2
    exit 1
  fi
  if ! awk -v user="$device" '$1 == user { found = 1 } END { exit !found }' "$mqtt_passwords"; then
    printf '%s %s\n' "$device" "$(random_value)" >>"$mqtt_passwords"
    mqtt_added=$((mqtt_added + 1))
  fi
done <"$here/mosquitto/devices.txt"
if [[ "$mqtt_added" -gt 0 ]]; then echo "infra/local/secrets/mqtt/device-passwords にデバイス ${mqtt_added} 台のパスワードを追記しました"; fi

