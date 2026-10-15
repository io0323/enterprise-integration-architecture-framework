#!/usr/bin/env bash
# Parquet の書き出しの手段の計測(P09 ②。docs/reports/p09-parquet-writer.md)。手動で動かす(CI では動かさない)。
#   tools/parquet-bench/run.sh <出力のディレクトリ> [回数]
# - 各候補を distroless のイメージ(linux/amd64 と linux/arm64)に入れ、batch-etl と同じメモリの上限(256m)で動かす。
# - 結果は <出力のディレクトリ>/results.jsonl(1 回 1 行)と <出力のディレクトリ>/sizes.tsv、読み直しは verify.jsonl。
# - ホストと違うアーキテクチャのイメージはエミュレーション(Apple silicon の Docker Desktop なら Rosetta)で動く。
#   エミュレーションはプロセスのメモリを増やすので、上限を EMULATED_MEMORY(既定 768m)に広げる。時間とメモリの値は比べない。
set -euo pipefail

out="$(cd "${1:?出力のディレクトリを指定してください}" && pwd)"
repeat="${2:-3}"
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
JAVA_RUNTIME_IMAGE="$(grep '^JAVA_RUNTIME_IMAGE=' "$root/infra/local/images.env" | cut -d= -f2-)"
gradle=("$root/gradlew" -q -p "$here")
rows_list=(100000 1000000)
case "$(uname -m)" in
  arm64 | aarch64) native=arm64 ;;
  *) native=amd64 ;;
esac

# macOS の bash 3.2 でも動くよう、連想配列は使わない
main_class() {
  case "$1" in
    parquet-java | parquet-java-hadoop) echo io.eia.tools.parquetbench.parquetjava.MainKt ;;
    duckdb) echo io.eia.tools.parquetbench.duckdb.MainKt ;;
    arrow) echo io.eia.tools.parquetbench.arrow.MainKt ;;
  esac
}
java_options() {
  case "$1" in
    arrow) echo "--add-opens=java.base/java.nio=ALL-UNNAMED" ;;
    *) echo "" ;;
  esac
}

mkdir -p "$out/files"
# コンテナは uid 65532 で書く
chmod 777 "$out/files"
: >"$out/results.jsonl"
printf 'candidate\tplatform\tjars\tlibBytes\timageBytes\n' >"$out/sizes.tsv"

for platform in arm64 amd64; do
  "${gradle[@]}" installDist "-Pbench.platform=linux_${platform}"
  memory=256m
  [ "$platform" = "$native" ] || memory="${EMULATED_MEMORY:-768m}"
  for candidate in parquet-java parquet-java-hadoop duckdb arrow; do
    image="eiaf-parquet-bench-${candidate}:${platform}"
    docker build -q --platform "linux/${platform}" --build-arg "JAVA_RUNTIME_IMAGE=${JAVA_RUNTIME_IMAGE}" \
      --build-arg "CANDIDATE=${candidate}" -t "$image" "$here" >/dev/null
    lib="$here/${candidate}/build/install/bench/lib"
    jars="$(find "$lib" -name '*.jar' | wc -l | tr -d ' ')"
    lib_bytes="$(find "$lib" -name '*.jar' -exec stat -f %z {} + | awk '{s+=$1} END {print s}')"
    image_bytes="$(docker image inspect -f '{{.Size}}' "$image")"
    printf '%s\t%s\t%s\t%s\t%s\n' "$candidate" "$platform" "$jars" "$lib_bytes" "$image_bytes" >>"$out/sizes.tsv"
    for rows in "${rows_list[@]}"; do
      for _ in $(seq "$repeat"); do
        docker run --rm --platform "linux/${platform}" --memory "$memory" --memory-swap "$memory" \
          -e "JDK_JAVA_OPTIONS=$(java_options "$candidate")" -v "$out/files:/out" "$image" \
          "$(main_class "$candidate")" /out "$rows" 2>>"$out/stderr.log" | grep '^{' >>"$out/results.jsonl" \
          || echo "{\"candidate\":\"$candidate\",\"platform\":\"$platform\",\"rows\":$rows,\"ok\":false,\"error\":\"exit\"}" >>"$out/results.jsonl"
      done
    done
  done
done

# 強制終了した回の残り(1 回目の書きかけ・DuckDB の一時ファイル)を消してから、読み直す(ホストで、parquet-java と DuckDB)
rm -rf "$out"/files/*-cold.parquet "$out"/files/*-cold.parquet.d "$out/files/duckdb-tmp"
find "$out/files" -name '*.parquet' -size 0 -delete
"${gradle[@]}" :verify:installDist
"$here/verify/build/install/verify/bin/verify" "$out/files" >"$out/verify.jsonl"
echo "結果: $out"
