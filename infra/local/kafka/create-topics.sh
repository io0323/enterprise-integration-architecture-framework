#!/bin/bash
# topics.conf のトピックを作り、設定をそろえる(何度実行しても同じ結果になる)。compose の kafka-topics(kafka のイメージ)で動く。
#   create-topics.sh <bootstrap> <topics.conf>
set -euo pipefail

bootstrap="$1"
file="$2"
bin=/opt/kafka/bin
export KAFKA_HEAP_OPTS=-Xmx128m

while read -r name partitions configs classification; do
  [[ -z "$name" || "$name" == \#* ]] && continue
  [[ -n "$classification" ]] || { echo "$name: 機密区分の列がありません" >&2; exit 1; }
  args=()
  IFS=',' read -r -a pairs <<<"$configs"
  for pair in "${pairs[@]}"; do args+=(--config "$pair"); done
  "$bin/kafka-topics.sh" --bootstrap-server "$bootstrap" --create --if-not-exists --topic "$name" \
    --partitions "$partitions" --replication-factor 1 "${args[@]}"
  # 既にあったトピックも、設定をこのファイルの値にそろえる
  "$bin/kafka-configs.sh" --bootstrap-server "$bootstrap" --alter --entity-type topics --entity-name "$name" --add-config "$configs" >/dev/null
  echo "トピック $name(パーティション $partitions、$configs、機密区分 $classification)"
done <"$file"
