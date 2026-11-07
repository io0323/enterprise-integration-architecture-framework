#!/usr/bin/env bash
# NFR の計測の後の確認(make nfr-measure が呼ぶ。P14 ⑩。ADR-0048 §2)。計測の間に発行の経路が崩れていなかったかを確かめる。
#
#   nfr-postcheck.sh <環境の記録のファイル> <計測を始めた時刻(エポック秒)> <シナリオ>
#
# - Kafka のログに、計測の間のブローカーの切り離し(heartbeat の RPC の時間切れ)がないか。Kafka Connect が動いていれば、
#   登録したコネクタとタスクがすべて RUNNING のままか。
# - 結果を環境の記録に足し、その回の結果の JSON(<シナリオ>-*.json のうち計測を始めた後に書いたもの)に `pipelineCheck` を足す。
#   崩れていたら `usable: false`(レポートに使わない回)にする。計測の前の確認(nfr-preflight.sh)だけでは、計測の途中の切り離しを見落とす。
# - 崩れていても終了コードは 0(ほかの回は使えるので、続けて測る)。警告を出し、GitHub Actions では注釈にする。
set -euo pipefail

env_file="${1:?使い方: nfr-postcheck.sh <環境の記録> <開始のエポック秒> <シナリオ>}"
since="${2:?使い方: nfr-postcheck.sh <環境の記録> <開始のエポック秒> <シナリオ>}"
scenario="${3:?使い方: nfr-postcheck.sh <環境の記録> <開始のエポック秒> <シナリオ>}"
project="${COMPOSE_PROJECT_NAME:-eiaf}"
service_container() { docker ps -q --filter "label=com.docker.compose.project=$project" --filter "label=com.docker.compose.service=$1" | head -1; }

detached=0
kafka="$(service_container kafka)"
if [[ -n "$kafka" ]]; then
  detached="$(docker logs --since "$since" "$kafka" 2>&1 | grep -c 'Unable to send a heartbeat' || true)"
fi
connectors="Kafka Connect なし"
connectors_ok=true
if [[ -n "$(service_container kafka-connect)" ]]; then
  connectors="$(curl -sf -u "eiaf-admin:${CONNECT_REST_PASSWORD:-}" 'http://localhost:19083/connectors?expand=status' | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except ValueError:
    sys.exit(1)
states = []
for name, item in sorted(data.items()):
    status = item["status"]
    tasks = [task["state"] for task in status["tasks"]]
    states.append(f"{name}={status['connector']['state']}/{','.join(tasks) or 'タスクなし'}")
    if status["connector"]["state"] != "RUNNING" or not tasks or set(tasks) != {"RUNNING"}:
        print("NG " + " ".join(states))
        sys.exit(0)
print("OK " + " ".join(states))
' || echo 'NG 状態を取れない')"
  [[ "$connectors" == OK* ]] || connectors_ok=false
  connectors="${connectors#?? }"
fi
usable=true
if ((detached > 0)) || [[ "$connectors_ok" != true ]]; then usable=false; fi

# 計測を始めた後に書いた、このシナリオの結果(名前は <シナリオ>-<計測を始めたエポック秒>.json)
result="$(ls "$(dirname "$env_file")"/"$scenario"-*.json 2>/dev/null | awk -F'[-.]' -v s="$since" '$(NF-1) + 0 >= s + 0' | sort | tail -1 || true)"
{
  echo "計測の後の確認:"
  echo "  Kafka の切り離し(計測の間の heartbeat の時間切れ): ${detached} 回"
  echo "  Kafka Connect のコネクタ: $connectors"
  echo "  この回を使うか: $([[ "$usable" == true ]] && echo 使う || echo '使わない(計測の間に発行の経路が崩れた)')"
  echo "  結果: ${result:-なし}"
} | tee -a "$env_file"
if [[ -n "$result" ]]; then
  python3 - "$result" "$detached" "$connectors" "$usable" <<'EOF'
import json
import sys

path, detached, connectors, usable = sys.argv[1:]
with open(path, encoding="utf-8") as file:
    data = json.load(file)
data["pipelineCheck"] = {"kafkaDetached": int(detached), "connectors": connectors, "usable": usable == "true"}
with open(path, "w", encoding="utf-8") as file:
    json.dump(data, file, ensure_ascii=False, indent=2)
    file.write("\n")
EOF
fi
if [[ "$usable" != true ]]; then
  message="この回は使いません: 計測の間に Kafka が ${detached} 回切り離された、またはコネクタが RUNNING ではない($connectors)"
  echo "警告: $message" >&2
  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then echo "::warning title=NFR $scenario::$message"; fi
fi
