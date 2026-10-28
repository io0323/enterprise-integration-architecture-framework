#!/bin/sh
# Mosquitto の起動(iot profile。ADR-0039 §3)。パスワードと ACL のファイルを、起動のたびに作り直してから起動する
# (ボリュームには置かない。mosquitto ユーザーの所有・0700 が要る)。
# - デバイス: mosquitto/devices.txt の 1 行に 1 台。パスワードは secrets/mqtt/device-passwords(make env が作る)。
#   ACL は 1 台ずつ書く(%u の置き換えを使わない。ユーザー名に / を含むため)。自分のトピックへの書き込みだけ
# - iot-bridge: テレメトリの読み取りだけ
# - eiaf-monitor: $SYS の読み取りだけ(Telegraf が溜め込みの数と捨てた数を Prometheus に出す。ADR-0039 §9)
# ACL に書いていない操作は拒否する(acl_file を使うと既定で拒否)。
set -eu

devices=/mosquitto/devices.txt
device_passwords=/mosquitto/secrets/device-passwords
passwd=/mosquitto/passwd
acl=/mosquitto/acl

: "${MQTT_BRIDGE_PASSWORD:?make env を実行してください}"
: "${MQTT_MONITOR_PASSWORD:?make env を実行してください}"

umask 077
rm -f "$passwd" "$acl"
touch "$passwd"
mosquitto_passwd -b "$passwd" iot-bridge "$MQTT_BRIDGE_PASSWORD"
mosquitto_passwd -b "$passwd" eiaf-monitor "$MQTT_MONITOR_PASSWORD"
{
  printf 'user iot-bridge\ntopic read devices/+/+/telemetry\n\n'
  printf 'user eiaf-monitor\ntopic read $SYS/#\n\n'
} >"$acl"

count=0
for device in $(grep -Ev '^[[:space:]]*(#|$)' "$devices"); do
  if ! printf '%s\n' "$device" | grep -Eq '^[a-z0-9][a-z0-9-]*/[a-z0-9][a-z0-9-]*$'; then
    echo "devices.txt: {tenant}/{deviceId}(小文字の英数字と -)ではありません: $device" >&2
    exit 1
  fi
  password="$(awk -v user="$device" '$1 == user { print $2 }' "$device_passwords")"
  if [ -z "$password" ]; then
    echo "$device のパスワードが secrets/mqtt/device-passwords にありません(make env を実行してください)" >&2
    exit 1
  fi
  mosquitto_passwd -b "$passwd" "$device" "$password"
  printf 'user %s\ntopic write devices/%s/#\n\n' "$device" "$device" >>"$acl"
  count=$((count + 1))
done
echo "MQTT のユーザー: デバイス ${count} 台・iot-bridge・eiaf-monitor"

chown mosquitto:mosquitto "$passwd" "$acl"
chmod 0700 "$passwd" "$acl"
exec /docker-entrypoint.sh mosquitto -c /mosquitto/config/mosquitto.conf
