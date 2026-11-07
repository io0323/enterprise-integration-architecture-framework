#!/usr/bin/env bash
# NFR の計測の前の確認(make nfr-up・make nfr-measure が呼ぶ。P14 ⑩。ADR-0048 §1)。計測の値がほかの負荷で歪まないようにする。
#
#   nfr-preflight.sh before-up <環境を書くファイル>       基盤を起動する前(make nfr-up)
#   nfr-preflight.sh before-measure <環境を書くファイル>  基盤を起動した後、計測を始める前(make nfr-measure)
#
# - 両方: eiaf の compose プロジェクト(COMPOSE_PROJECT_NAME。既定 eiaf)以外のコンテナが動いていれば止める。
# - before-up: ホストの 1 分の load average が、CPU のコア数の半分を超えていれば止める(ほかからの負荷を見る)。
# - before-measure: 起動直後の負荷が落ち着くまで待つ(1 分の load average を 15 秒ごとに見て、1 分前より 0.2 以上
#   下がらなくなるまで。最長 NFR_SETTLE_MAX 秒。既定 600)。落ち着いた値が NFR_MAX_LOAD(既定 6)を超えていれば止める。
# - 止めるときは終了コード 3。NFR_FORCE=1 で警告だけにして続ける(警告は環境の記録にも書く)。
# - 計測の開始時の環境(load average と上限・Docker のメモリの割り当て・動いていたコンテナ)をファイルに書く(レポートに書き写す)。
#   before-measure は、最後の before-up の記録も含める。秘密情報は書かない(コンテナの名前とメモリ・CPU の使用量だけ)。
set -euo pipefail

stage="${1:?使い方: nfr-preflight.sh before-up|before-measure <環境を書くファイル>}"
out="${2:?使い方: nfr-preflight.sh before-up|before-measure <環境を書くファイル>}"
project="${COMPOSE_PROJECT_NAME:-eiaf}"
[[ "$stage" == before-up || "$stage" == before-measure ]] || { echo "before-up か before-measure を指定してください" >&2; exit 2; }
mkdir -p "$(dirname "$out")"
docker info >/dev/null 2>&1 || { echo "Docker が動いていません" >&2; exit 2; }

if [[ "$(uname -s)" == Darwin ]]; then
  cores="$(sysctl -n hw.ncpu)"
  load1() { sysctl -n vm.loadavg | awk '{print $2}'; }
  host="$(sysctl -n machdep.cpu.brand_string) / メモリ $(($(sysctl -n hw.memsize) / 1024 / 1024 / 1024)) GiB / macOS $(sw_vers -productVersion)"
else
  cores="$(nproc)"
  load1() { awk '{print $1}' /proc/loadavg; }
  host="$(uname -sr) / メモリ $(awk '/MemTotal/ {printf "%d", $2 / 1024 / 1024}' /proc/meminfo) GiB"
fi
gt() { awk -v a="$1" -v b="$2" 'BEGIN { exit !(a > b) }'; }

settle_note=""
if [[ "$stage" == before-up ]]; then
  max_load="$(awk -v c="$cores" 'BEGIN { print c / 2 }')"
  load="$(load1)"
else
  max_load="${NFR_MAX_LOAD:-6}"
  settle_max="${NFR_SETTLE_MAX:-600}"
  echo "起動直後の負荷が落ち着くのを待ちます(15 秒ごと。最長 ${settle_max} 秒)"
  samples=()
  started=$SECONDS
  while :; do
    samples+=("$(load1)")
    n=${#samples[@]}
    echo "  load average(1 分): ${samples[n - 1]}"
    # 1 分前(4 回前)の値より 0.2 以上下がっていなければ、落ち着いたとみなす
    if ((n >= 5)) && ! gt "${samples[n - 5]}" "$(awk -v v="${samples[n - 1]}" 'BEGIN { print v + 0.2 }')"; then
      settle_note="$((SECONDS - started)) 秒で落ち着いた(1 分前 ${samples[n - 5]} → ${samples[n - 1]})"
      break
    fi
    if ((SECONDS - started >= settle_max)); then
      settle_note="${settle_max} 秒待っても下がり続けていた"
      break
    fi
    sleep 15
  done
  load="${samples[${#samples[@]} - 1]}"
fi
others="$(docker ps --format '{{.Names}} {{.Label "com.docker.compose.project"}}' | awk -v p="$project" '$2 != p {print $1}')"

{
  echo "段階: $stage"
  echo "時刻: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "コミット: $(git rev-parse --short HEAD)$(git diff --quiet HEAD -- . ':!docs' || echo '(未コミットの変更あり)')"
  echo "ホスト: $host / CPU $cores コア"
  echo "load average(1 分): $load(上限 $max_load。$([[ "$stage" == before-up ]] && echo 'コア数の半分' || echo 'NFR_MAX_LOAD'))"
  if [[ -n "$settle_note" ]]; then echo "負荷の落ち着き: $settle_note"; fi
  docker info --format 'Docker: {{.ServerVersion}} / VM の CPU {{.NCPU}} / VM のメモリ {{.MemTotal}} バイト'
  echo "compose プロジェクト: $project"
  echo "動いていたコンテナ(名前 / メモリ / CPU):"
  docker stats --no-stream --format '{{.Name}} {{.MemUsage}} {{.CPUPerc}}' | sort | sed 's/^/  /'
  if [[ "$stage" == before-measure ]]; then
    last="$(ls -t "$(dirname "$out")"/env-before-up-*.txt 2>/dev/null | head -1 || true)"
    echo "基盤を起動する前の記録: ${last:-なし(make nfr-up を使っていない)}"
    if [[ -n "$last" ]]; then sed 's/^/  | /' "$last"; fi
  fi
} > "$out"
cat "$out"

problems=()
[[ -n "$others" ]] && problems+=("eiaf 以外のコンテナが動いています: $(echo "$others" | paste -sd ' ' -)")
gt "$load" "$max_load" && problems+=("ホストの load average($load)が上限($max_load)を超えています。重いアプリを閉じて、落ち着いてから測ってください")
if ((${#problems[@]} > 0)); then
  for p in "${problems[@]}"; do echo "警告: $p" | tee -a "$out" >&2; done
  if [[ "${NFR_FORCE:-}" == 1 ]]; then
    echo "警告: NFR_FORCE=1 なので続けます(レポートにこの警告を書いてください)" | tee -a "$out" >&2
  else
    echo "止めました。続けるなら NFR_FORCE=1 を付けます(レポートにその旨を書く)" >&2
    exit 3
  fi
fi
