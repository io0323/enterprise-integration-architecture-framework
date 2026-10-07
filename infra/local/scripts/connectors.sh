#!/usr/bin/env bash
# Kafka Connect にコネクタを登録し、RUNNING になるまで待つ(ADR-0007・ADR-0016)。make up が PROFILE=order のときに呼ぶ。
#
#   connectors.sh <名前>...   (例: connectors.sh order-outbox)
#
# 設定は infra/local/kafka-connect/<名前>.json(`{"name": ..., "config": {...}}`)。PUT /connectors/<名前>/config は、
# なければ作り、あれば設定を置き換える(何度実行しても同じ結果になる)。秘密情報は設定に書かず ${env:VAR} で参照する
# (Connect の EnvVarConfigProvider。平文が _connect.configs と REST に残らない)。
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
connect="${CONNECT_URL:-http://localhost:19083}"

for name in "$@"; do
  file="$here/kafka-connect/$name.json"
  [[ -f "$file" ]] || { echo "コネクタの設定がありません: $file" >&2; exit 1; }
  config="$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["name"]==sys.argv[2], "name が違います"; print(json.dumps(d["config"]))' "$file" "$name")"
  curl -fsS -o /dev/null -X PUT "$connect/connectors/$name/config" -H 'Content-Type: application/json' -d "$config"
  printf 'コネクタ %s を登録しました。RUNNING を待ちます' "$name"
  for ((i = 0; i < 60; i++)); do
    if curl -fsS "$connect/connectors/$name/status" 2>/dev/null | python3 -c 'import json,sys; d=json.load(sys.stdin); t=d.get("tasks") or []; sys.exit(0 if d.get("connector",{}).get("state")=="RUNNING" and t and all(x.get("state")=="RUNNING" for x in t) else 1)' 2>/dev/null; then
      echo " ... RUNNING"
      continue 2
    fi
    printf '.'
    sleep 2
  done
  echo
  echo "コネクタ $name が RUNNING になりません(make logs SERVICE=kafka-connect を確認してください)" >&2
  curl -fsS "$connect/connectors/$name/status" >&2 || true
  exit 1
done
