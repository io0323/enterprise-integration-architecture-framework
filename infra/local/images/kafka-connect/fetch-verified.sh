#!/bin/bash
# 成果物を取得し、SHA-256 が固定した値と一致したときだけ置く(ADR-0016 §9。#106)。Dockerfile の RUN から使う。
#   fetch-verified.sh <URL> <SHA-256> <置く場所>
#
# ADD --checksum をやめた理由: CI で、Maven Central の成果物の代わりに別の応答(2 回とも同じ sha256:807ceab0…)を受け取り、
# 組み立てが失敗した(#98・#105)。ADD は 1 回で失敗し、何を受け取ったかも分からない。ここでは、合わなければ待ってやり直し、
# 毎回、受け取った応答のヘッダ・大きさ・先頭のバイトを組み立てのログに出す(原因を確かめるため)。
# 版と SHA-256 の固定は変えない。すべての試行で合わなければ失敗する(受け取った値に合わせて通すことはしない)。
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "使い方: fetch-verified.sh <URL> <SHA-256> <置く場所>" >&2
  exit 2
fi
url="$1"
expected="$2"
dest="$3"
part="$dest.part"
headers="$dest.headers"
mkdir -p "$(dirname "$dest")"

delays=(0 10 30 60)
attempt=0
for delay in "${delays[@]}"; do
  attempt=$((attempt + 1))
  sleep "$delay"
  rm -f "$part" "$headers"
  # -S: 応答の状態とヘッダを標準エラーに出す(下で記録する)
  if wget -q -S -O "$part" "$url" 2>"$headers"; then
    actual="$(sha256sum "$part" | cut -d' ' -f1)"
    if [ "$actual" = "$expected" ]; then
      mv "$part" "$dest"
      rm -f "$headers"
      echo "OK   $url(${attempt} 回目)"
      exit 0
    fi
    echo "MISMATCH $url(${attempt} 回目)expected=$expected actual=$actual size=$(wc -c <"$part") バイト"
    echo "  先頭: $(head -c 48 "$part" | od -An -c | tr -s ' \n' ' ' | cut -c1-240)"
  else
    echo "FAILED $url(${attempt} 回目)"
  fi
  # 受け取った応答の状態とヘッダ(公開の成果物の取得なので秘密情報は含まない。CDN が付ける Cookie は原因の調査に要らないので出さない)
  grep -v -i 'set-cookie' "$headers" | sed 's/^/  /' | head -40
done
rm -f "$part" "$headers"
echo "ERROR: $url を取得できないか、SHA-256 が合わない(${attempt} 回試した)" >&2
exit 1
