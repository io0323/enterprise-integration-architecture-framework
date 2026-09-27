#!/bin/bash
# Kafka Connect(分散モード)を起動する(ADR-0016 §9)。
#
# 環境変数 CONNECT_<NAME> を、connect-distributed.properties の <name> にする。
# <NAME> は大文字を小文字に、`_` を `.` に変える(例: CONNECT_CONFIG_PROVIDERS_ENV_CLASS → config.providers.env.class)。
# Debezium の公式イメージの docker-entrypoint.sh と同じ規則。名前に `_` を含む設定は、この方法では書けない。
#
# 既定値は Debezium の公式イメージ(3.6)の実効値に合わせる。ただし次の 2 点は Kafka 4 に合わせた:
# - 待ち受けは listeners で指定する(Kafka 4 で rest.host.name / rest.port は廃止された)
# - rest.advertised.host.name はコンテナの IP アドレス(公式イメージと同じ)
#
# 値は標準出力に出さない(秘密情報を含みうるため)。設定した名前だけを出す。
set -euo pipefail

config_dir=/opt/kafka/connect-config
config_file="$config_dir/connect-distributed.properties"

declare -A props=(
  [key.converter]=org.apache.kafka.connect.json.JsonConverter
  [value.converter]=org.apache.kafka.connect.json.JsonConverter
  [key.converter.schemas.enable]=true
  [value.converter.schemas.enable]=true
  [offset.flush.interval.ms]=60000
  [offset.flush.timeout.ms]=5000
  [task.shutdown.graceful.timeout.ms]=10000
  [listeners]=http://0.0.0.0:8083
  [rest.advertised.port]=8083
  [plugin.path]=/opt/kafka/plugins
)
props[rest.advertised.host.name]="$(hostname -i | awk '{print $1}')"

for name in $(compgen -e); do
  [[ $name == CONNECT_* ]] || continue
  key="$(tr '[:upper:]_' '[:lower:].' <<<"${name#CONNECT_}")"
  props[$key]="${!name}"
done

missing=()
for required in bootstrap.servers group.id config.storage.topic offset.storage.topic status.storage.topic; do
  [[ -n "${props[$required]:-}" ]] || missing+=("$required")
done
if ((${#missing[@]} > 0)); then
  echo "必須の設定がありません: ${missing[*]}(CONNECT_<NAME> の環境変数で指定する。例: CONNECT_BOOTSTRAP_SERVERS)" >&2
  exit 1
fi

mkdir -p "$config_dir"
: >"$config_file"
for key in $(printf '%s\n' "${!props[@]}" | sort); do
  printf '%s=%s\n' "$key" "${props[$key]}" >>"$config_file"
done
echo "Kafka Connect の設定: $(printf '%s\n' "${!props[@]}" | sort | paste -sd ' ' -)"

exec /opt/kafka/bin/connect-distributed.sh "$config_file"
