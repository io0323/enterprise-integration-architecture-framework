#!/usr/bin/env bash
# 起動中の EIAF コンテナのメモリ使用量を表示する(docs/reports/p03-local-infrastructure.md の計測用)。
# docker stats の 1 回分のスナップショットと、合計(MiB)を出す。
set -euo pipefail

ids="$(docker ps -q --filter label=com.docker.compose.project=eiaf)"
if [[ -z "$ids" ]]; then
  echo "起動中の EIAF コンテナはありません"
  exit 0
fi

# shellcheck disable=SC2086
docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}' $ids |
  sort |
  python3 -c '
import re, sys
units = {"B": 1 / 1024 / 1024, "KiB": 1 / 1024, "MiB": 1, "GiB": 1024, "kB": 1 / 1024, "MB": 1, "GB": 1024}
def mib(v):
    m = re.fullmatch(r"([\d.]+)\s*([A-Za-z]+)", v.strip())
    return float(m.group(1)) * units[m.group(2)]
total = limit = 0.0
print("| コンテナ | 使用量 (MiB) | 上限 (MiB) | 使用率 | CPU |")
print("|---|---:|---:|---:|---:|")
for line in sys.stdin:
    name, usage, perc, cpu = line.rstrip("\n").split("\t")
    used, lim = (mib(x) for x in usage.split("/"))
    total += used
    limit += lim
    print(f"| {name} | {used:.0f} | {lim:.0f} | {perc} | {cpu} |")
print(f"| **合計** | **{total:.0f}** | {limit:.0f} | | |")
'
