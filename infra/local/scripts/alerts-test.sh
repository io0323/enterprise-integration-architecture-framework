#!/usr/bin/env bash
# Prometheus のアラートのルールの検査(promtool check rules)と単体テスト(promtool test rules)。make alerts-test と CI(infra)が使う。
# promtool は images.env の PROMETHEUS_IMAGE のものを使う(ローカル基盤の Prometheus と同じ版)。
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
image="$(grep '^PROMETHEUS_IMAGE=' "$here/images.env" | cut -d= -f2-)"
rules="$here/prometheus/rules"

docker run --rm -v "$rules:/rules:ro" -w /rules --entrypoint promtool "$image" check rules $(cd "$rules" && ls *.rules.yml)
docker run --rm -v "$rules:/rules:ro" -w /rules --entrypoint promtool "$image" test rules $(cd "$rules" && ls *.test.yml)
