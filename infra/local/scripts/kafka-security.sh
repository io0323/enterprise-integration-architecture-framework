#!/usr/bin/env bash
# secure profile の Kafka の SCRAM のユーザーと ACL を、カタログ(contracts/catalog)にそろえる(ADR-0047)。
# make up PROFILE=secure が、ブローカーの起動の後に呼ぶ。何度実行してもよい(足りない分を足し、カタログにない分を消す)。
#
# - ユーザーはカタログの system の名前。パスワードは infra/local/secrets/kafka/<ユーザー>(なければ作る。.gitignore 済み)。
# - 設定は内部のリスナー(kafka:9092。User:ANONYMOUS が super user)から行う。
# - パスワードは標準入力でコンテナに渡し、ホストのコマンドラインとログに出さない。
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
compose=(docker compose -f "$here/docker-compose.yml" -f "$here/docker-compose.secure.yml"
  --env-file "$here/images.env" --env-file "$here/.env" --profile core --profile secure)
kafka() { "${compose[@]}" exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka "/opt/kafka/bin/$1.sh" --bootstrap-server kafka:9092 "${@:2}"; }

# トピックを作り終えるのを待つ(secure では kafka-topics の終わりを待つサービスがないので、ここで待つ)
# (終わったコンテナに wait は使えないので、動いているときだけ待つ)
if [[ -n "$("${compose[@]}" ps -q --status running kafka-topics)" ]]; then
  "${compose[@]}" wait kafka-topics >/dev/null
fi

desired="$(python3 -I "$here/scripts/kafka-acls.py")"
users="$(awk '{print $1}' <<<"$desired" | sort -u)"

# 1. SCRAM のユーザー
secret_dir="$here/secrets/kafka"
mkdir -p "$secret_dir"
for user in $users; do
  file="$secret_dir/$user"
  if [[ ! -s "$file" ]]; then
    (umask 077 && LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32 >"$file" || true)
  fi
  # shellcheck disable=SC2016 # $1・$pw はコンテナの中で展開する
  "${compose[@]}" exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka bash -c \
    'read -r pw; /opt/kafka/bin/kafka-configs.sh --bootstrap-server kafka:9092 --alter --entity-type users --entity-name "$1" \
       --add-config "SCRAM-SHA-512=[iterations=8192,password=$pw]" >/dev/null' _ "$user" <"$file"
done
# カタログから消えたユーザーの資格情報を消す
for user in $(kafka kafka-configs --describe --entity-type users </dev/null | sed -n 's/^SCRAM credential configs for user-principal .\([^ ]*\). are .*/\1/p'); do
  if ! grep -qx "$user" <<<"$users"; then
    kafka kafka-configs --alter --entity-type users --entity-name "$user" --delete-config SCRAM-SHA-512 >/dev/null </dev/null
    echo "カタログにないユーザー $user の資格情報を消しました"
  fi
done

# 2. ACL(足りない分を足し、カタログにない分を消す)
changes="$(kafka kafka-acls --list | python3 -I "$here/scripts/kafka-acls.py" --plan)"
added=0 removed=0
while read -r action user operation kind name; do
  [[ -n "$action" ]] || continue
  if [[ "$action" == add ]]; then
    # exec に標準入力を渡さない(渡すと、このループが読む残りの行を exec が読んでしまう)
    kafka kafka-acls --add --allow-principal "User:$user" --operation "$operation" "--$kind" "$name" >/dev/null </dev/null
    added=$((added + 1))
  else
    kafka kafka-acls --remove --force --allow-principal "User:$user" --operation "$operation" "--$kind" "$name" >/dev/null </dev/null
    removed=$((removed + 1))
  fi
done <<<"$changes"
echo "Kafka の ACL をカタログにそろえました(ユーザー $(wc -w <<<"$users" | tr -d ' ') 人、ACL $(wc -l <<<"$desired" | tr -d ' ') 件。追加 $added 件、削除 $removed 件)"
