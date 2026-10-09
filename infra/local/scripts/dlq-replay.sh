#!/usr/bin/env bash
# DLQ のメッセージを元のトピックに戻す(Replay。Framework 13.1。ADR-0028 §6)。`make dlq-replay ARGS="..."` から呼ぶ。
# 手順は docs/runbooks/event-dlq-replay.md。既定は dry-run(一覧だけ)で、送るのは --execute を付けたときだけ。
# tools/dlq-replay の終了コードをそのまま返す: 0 = 終えた / 1 = 戻せなかった対象がある / 2 = 実行できない
# ローカル基盤の Kafka(ホスト用のリスナー localhost:19092)に接続する。秘密情報は使わない(認証は secure profile の #26)。
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
root="$(cd "$here/../.." && pwd)"

# Gradle の出力は捨て、失敗したときだけ表示する
if ! build_log="$("$root/gradlew" -p "$root" -q :tools:dlq-replay:installDist 2>&1)"; then
  echo "$build_log" >&2
  echo "tools/dlq-replay をビルドできません(JAVA_HOME が JDK 21 以上を指しているか確かめてください)" >&2
  exit 2
fi

exec "$root/tools/dlq-replay/build/install/dlq-replay/bin/dlq-replay" "$@"
