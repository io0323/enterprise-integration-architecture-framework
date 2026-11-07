#!/usr/bin/env bash
# NFR の計測の前の確認(make nfr-measure が呼ぶ。P14 ⑩。ADR-0048 §1)。
# - 計測の値がほかの負荷で歪まないように、次のどちらかに当たれば警告を出して止める(終了コード 3)。NFR_FORCE=1 で警告だけにして続ける。
#   1. eiaf の compose プロジェクト(COMPOSE_PROJECT_NAME。既定 eiaf)以外のコンテナが動いている
#   2. ホストの 1 分の load average が、CPU のコア数の半分(NFR_MAX_LOAD で変えられる)を超えている
# - 計測の開始時の環境(load average・Docker のメモリの割り当て・動いていたコンテナ)を、引数のファイルに書く(レポートに書き写す)。
#   秘密情報は書かない(コンテナの名前・イメージ・状態と、メモリの使用量だけ)。
set -euo pipefail

out="${1:?使い方: nfr-preflight.sh <環境を書くファイル>}"
project="${COMPOSE_PROJECT_NAME:-eiaf}"
mkdir -p "$(dirname "$out")"
docker info >/dev/null 2>&1 || { echo "Docker が動いていません" >&2; exit 2; }

if [[ "$(uname -s)" == Darwin ]]; then
  cores="$(sysctl -n hw.ncpu)"
  load="$(sysctl -n vm.loadavg | awk '{print $2}')"
  host="$(sysctl -n machdep.cpu.brand_string) / メモリ $(($(sysctl -n hw.memsize) / 1024 / 1024 / 1024)) GiB / macOS $(sw_vers -productVersion)"
else
  cores="$(nproc)"
  load="$(awk '{print $1}' /proc/loadavg)"
  host="$(uname -sr) / メモリ $(awk '/MemTotal/ {printf "%d", $2 / 1024 / 1024}' /proc/meminfo) GiB"
fi
max_load="${NFR_MAX_LOAD:-$(awk -v c="$cores" 'BEGIN { print c / 2 }')}"
others="$(docker ps --format '{{.Names}} {{.Label "com.docker.compose.project"}}' | awk -v p="$project" '$2 != p {print $1}')"

{
  echo "計測の開始: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "コミット: $(git rev-parse --short HEAD)$(git diff --quiet HEAD -- . ':!docs' || echo '(未コミットの変更あり)')"
  echo "ホスト: $host / CPU $cores コア"
  echo "load average(1 分): $load(上限 $max_load)"
  docker info --format 'Docker: {{.ServerVersion}} / VM の CPU {{.NCPU}} / VM のメモリ {{.MemTotal}} バイト'
  echo "compose プロジェクト: $project"
  echo "動いていたコンテナ(名前 / メモリ / CPU):"
  docker stats --no-stream --format '  {{.Name}} {{.MemUsage}} {{.CPUPerc}}' | sort
} > "$out"
cat "$out"

problems=()
if [[ -n "$others" ]]; then
  problems+=("eiaf 以外のコンテナが動いています: $(echo "$others" | paste -sd ' ' -)")
fi
if awk -v l="$load" -v m="$max_load" 'BEGIN { exit !(l > m) }'; then
  problems+=("ホストの load average($load)が上限($max_load)を超えています。重いアプリを閉じて、落ち着いてから測ってください")
fi
if ((${#problems[@]} > 0)); then
  for p in "${problems[@]}"; do echo "警告: $p" >&2; done
  if [[ "${NFR_FORCE:-}" == 1 ]]; then
    echo "警告: NFR_FORCE=1 なので続けます(レポートにこの警告を書いてください)" | tee -a "$out" >&2
  else
    echo "計測を止めました。続けるなら NFR_FORCE=1 を付けます(レポートにその旨を書く)" >&2
    exit 3
  fi
fi
