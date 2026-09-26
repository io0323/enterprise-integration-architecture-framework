#!/usr/bin/env bash
# ローカル基盤の検証(P03 DoD の証跡)。`make verify PROFILE=<name>` から呼ぶ。
# 1. 指定 profile(+ core)の全サービスのコンテナが healthy であること
# 2. profile ごとの機能の疎通(下の verify_<profile> 関数)
# 秘密情報は .env から読むが、出力には出さない。
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
profiles=(core)
for p in "$@"; do [[ "$p" == core ]] || profiles+=("$p"); done

set -a
# shellcheck disable=SC1091
source "$here/images.env"
# shellcheck disable=SC1091
source "$here/.env"
set +a

compose=(docker compose -f "$here/docker-compose.yml" --env-file "$here/images.env" --env-file "$here/.env")
for p in "${profiles[@]}"; do compose+=(--profile "$p"); done

passed=0
failed=0
pass() { printf 'PASS [%s] %s\n' "$current" "$1"; passed=$((passed + 1)); }
fail() { printf 'FAIL [%s] %s\n' "$current" "$1"; failed=$((failed + 1)); }
check() { # check <説明> <コマンド...>
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then pass "$desc"; else fail "$desc"; fi
}
retry() { # retry <回数> <間隔秒> <コマンド...>
  local n="$1" wait="$2"; shift 2
  for ((i = 0; i < n; i++)); do "$@" && return 0; sleep "$wait"; done
  return 1
}
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

# ------------------------------------------------------------------ 共通: healthy
verify_health() {
  current=health
  local services
  services="$("${compose[@]}" config --services)"
  for svc in $services; do
    local state
    state="$("${compose[@]}" ps -a --format '{{.State}}/{{.Health}}' "$svc" 2>/dev/null | head -1)"
    if [[ "$state" == "running/healthy" ]]; then pass "$svc: $state"; else fail "$svc: ${state:-コンテナなし}"; fi
  done
}

# ------------------------------------------------------------------ core
verify_core() {
  current=core
  local registry=http://localhost:19081/apis/registry/v3

  # Apicurio: 既定のグローバル互換性ルール(ADR-0014)
  local rule
  rule="$(curl -fsS "$registry/admin/rules/COMPATIBILITY" | json 'd["config"]' || true)"
  if [[ "$rule" == FULL_TRANSITIVE ]]; then pass "Apicurio のグローバル COMPATIBILITY ルールが FULL_TRANSITIVE"; else fail "Apicurio のグローバル COMPATIBILITY ルール: '${rule}'"; fi

  # Apicurio: ルールが実際に効くこと(v1 → default のない項目の追加 = FULL 違反 → 拒否 / default 付き項目の追加 → 受理)
  local group="eiaf.verify" artifact="verify-$$"
  local v1='{"type":"record","name":"Probe","namespace":"io.eia.verify","fields":[{"name":"id","type":"string"}]}'
  local bad='{"type":"record","name":"Probe","namespace":"io.eia.verify","fields":[{"name":"id","type":"string"},{"name":"qty","type":"int"}]}'
  local good='{"type":"record","name":"Probe","namespace":"io.eia.verify","fields":[{"name":"id","type":"string"},{"name":"note","type":["null","string"],"default":null}]}'
  body() { python3 -c 'import json,sys; print(json.dumps({"artifactId":sys.argv[1],"artifactType":"AVRO","firstVersion":{"content":{"content":sys.argv[2],"contentType":"application/json"}}}))' "$1" "$2"; }
  version_body() { python3 -c 'import json,sys; print(json.dumps({"content":{"content":sys.argv[1],"contentType":"application/json"}}))' "$1"; }
  check "Apicurio: スキーマ v1 を登録できる" \
    curl -fsS -X POST "$registry/groups/$group/artifacts" -H 'Content-Type: application/json' -d "$(body "$artifact" "$v1")"
  local code
  code="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$registry/groups/$group/artifacts/$artifact/versions" -H 'Content-Type: application/json' -d "$(version_body "$bad")")"
  if [[ "$code" == 409 || "$code" == 400 ]]; then pass "Apicurio: FULL 非互換の版(default のない項目の追加)を拒否する (HTTP $code)"; else fail "Apicurio: FULL 非互換の版が HTTP $code"; fi
  check "Apicurio: FULL 互換の版(default 付き項目の追加)を受理する" \
    curl -fsS -X POST "$registry/groups/$group/artifacts/$artifact/versions" -H 'Content-Type: application/json' -d "$(version_body "$good")"
  curl -sS -o /dev/null -X DELETE "$registry/groups/$group/artifacts/$artifact" || true

  # Keycloak: client credentials で sales.order:* のスコープと aud=order-api を持つトークンが取れる(Framework 12.3)
  local token claims
  token="$(curl -fsS -X POST http://localhost:19180/realms/eiaf/protocol/openid-connect/token \
    -d grant_type=client_credentials -d client_id=eiaf-e2e --data-urlencode "client_secret=$EIAF_E2E_CLIENT_SECRET" \
    --data-urlencode 'scope=sales.order:read sales.order:write' | json 'd["access_token"]' || true)"
  claims="$(printf '%s' "$token" | python3 -c '
import base64,json,sys
p=sys.stdin.read().split(".")[1]; p+="="*(-len(p)%4)
d=json.loads(base64.urlsafe_b64decode(p))
aud=d.get("aud"); aud=aud if isinstance(aud,list) else [aud]
print(d.get("iss"), ",".join(aud), " ".join(sorted(d.get("scope","").split())))' 2>/dev/null || true)"
  if [[ "$claims" == "http://localhost:19180/realms/eiaf order-api sales.order:read sales.order:write" ]]; then
    pass "Keycloak: トークンの iss / aud / scope ($claims)"
  else
    fail "Keycloak: トークンの iss / aud / scope が想定外 ('$claims')"
  fi

  # APISIX: standalone のルートが読み込まれている
  check "APISIX: GET /_gateway/health が 200" curl -fsS http://localhost:19080/_gateway/health

  # PostgreSQL: サービス別 DB に自分のユーザーで接続でき、他サービスの DB には接続できない(Shared Database 禁止)
  check "PostgreSQL: order_service で自分の DB に接続できる" \
    "${compose[@]}" exec -T -e PGPASSWORD="$ORDER_DB_PASSWORD" postgres psql -h 127.0.0.1 -U order_service -d order_service -tAc 'select 1'
  if "${compose[@]}" exec -T -e PGPASSWORD="$ORDER_DB_PASSWORD" postgres psql -h 127.0.0.1 -U order_service -d inventory_service -tAc 'select 1' >/dev/null 2>&1; then
    fail "PostgreSQL: order_service が inventory_service に接続できてしまう"
  else
    pass "PostgreSQL: order_service は inventory_service に接続できない"
  fi
  check "PostgreSQL: wal_level=logical" \
    bash -c "[[ \"\$(${compose[*]} exec -T postgres psql -U postgres -tAc 'show wal_level')\" == logical ]]"

  # Kafka: 自動作成が無効で、明示的に作ったトピックで produce / consume できる
  local topic="eiaf.verify.probed.v1" kbin=/opt/kafka/bin
  "${compose[@]}" exec -T kafka $kbin/kafka-topics.sh --bootstrap-server kafka:9092 --delete --topic "$topic" >/dev/null 2>&1 || true
  check "Kafka: トピックを作成できる" \
    "${compose[@]}" exec -T kafka $kbin/kafka-topics.sh --bootstrap-server kafka:9092 --create --topic "$topic" --partitions 1 --replication-factor 1
  check "Kafka: ホストのリスナー(localhost:19092)から produce できる" \
    bash -c "echo verify-$$ | ${compose[*]} exec -T kafka $kbin/kafka-console-producer.sh --bootstrap-server localhost:19092 --topic $topic"
  check "Kafka: consume できる" \
    bash -c "${compose[*]} exec -T kafka $kbin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic $topic --from-beginning --max-messages 1 --timeout-ms 15000 | grep -q verify-$$"
  "${compose[@]}" exec -T kafka $kbin/kafka-topics.sh --bootstrap-server kafka:9092 --delete --topic "$topic" >/dev/null 2>&1 || true
  local produce_output
  produce_output="$("${compose[@]}" exec -T kafka $kbin/kafka-console-producer.sh --bootstrap-server kafka:9092 \
    --topic eiaf.verify.undeclared.v1 --command-property max.block.ms=5000 <<<"x" 2>&1 || true)"
  if grep -q -i -E "not present in metadata|UNKNOWN_TOPIC" <<<"$produce_output" &&
    ! "${compose[@]}" exec -T kafka $kbin/kafka-topics.sh --bootstrap-server kafka:9092 --list | grep -q -x eiaf.verify.undeclared.v1; then
    pass "Kafka: 未作成のトピックは自動作成されない(auto.create.topics.enable=false)"
  else
    fail "Kafka: 未作成のトピックへの produce が失敗しない"
  fi

  # OTel Collector → Tempo / Loki / Prometheus(OTLP/HTTP JSON を 1 件ずつ送り、各バックエンドで検索する)
  local trace_id span_id now
  trace_id="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
  span_id="$(python3 -c 'import secrets; print(secrets.token_hex(8))')"
  now="$(python3 -c 'import time; print(time.time_ns())')"
  local resource='{"attributes":[{"key":"service.name","value":{"stringValue":"eiaf-verify"}}]}'
  check "OTel Collector: OTLP/HTTP で trace を受け付ける" curl -fsS -X POST http://localhost:19318/v1/traces -H 'Content-Type: application/json' \
    -d "{\"resourceSpans\":[{\"resource\":$resource,\"scopeSpans\":[{\"spans\":[{\"traceId\":\"$trace_id\",\"spanId\":\"$span_id\",\"name\":\"verify\",\"kind\":1,\"startTimeUnixNano\":\"$now\",\"endTimeUnixNano\":\"$((now + 1000000))\"}]}]}]}"
  check "OTel Collector: OTLP/HTTP で log を受け付ける" curl -fsS -X POST http://localhost:19318/v1/logs -H 'Content-Type: application/json' \
    -d "{\"resourceLogs\":[{\"resource\":$resource,\"scopeLogs\":[{\"logRecords\":[{\"timeUnixNano\":\"$now\",\"severityText\":\"INFO\",\"body\":{\"stringValue\":\"verify $trace_id\"},\"traceId\":\"$trace_id\",\"spanId\":\"$span_id\"}]}]}]}"
  check "OTel Collector: OTLP/HTTP で metric を受け付ける" curl -fsS -X POST http://localhost:19318/v1/metrics -H 'Content-Type: application/json' \
    -d "{\"resourceMetrics\":[{\"resource\":$resource,\"scopeMetrics\":[{\"metrics\":[{\"name\":\"eiaf_verify_probe\",\"gauge\":{\"dataPoints\":[{\"asInt\":\"1\",\"timeUnixNano\":\"$now\"}]}}]}]}]}"
  check "Tempo: 送った trace を ID で取得できる" retry 30 2 curl -fsS -o /dev/null "http://localhost:19320/api/v2/traces/$trace_id"
  check "Loki: 送った log を trace_id で検索できる" retry 30 2 bash -c \
    "curl -fsS -G http://localhost:19310/loki/api/v1/query_range --data-urlencode 'query={service_name=\"eiaf-verify\"} |= \"$trace_id\"' | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin)[\"data\"][\"result\"] else 1)'"
  check "Prometheus: 送った metric を検索できる" retry 30 2 bash -c \
    "curl -fsS -G http://localhost:19090/api/v1/query --data-urlencode 'query=eiaf_verify_probe' | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin)[\"data\"][\"result\"] else 1)'"

  # Prometheus: ミドルウェア自身のメトリクスの scrape 先がすべて up
  check "Prometheus: scrape 先がすべて up" retry 10 3 bash -c \
    "curl -fsS http://localhost:19090/api/v1/targets | python3 -c 'import json,sys; t=json.load(sys.stdin)[\"data\"][\"activeTargets\"]; sys.exit(0 if t and all(x[\"health\"]==\"up\" for x in t) else 1)'"

  # Grafana: provisioning した 3 つのデータソースが疎通する
  for uid in prometheus tempo loki; do
    check "Grafana: データソース $uid の health が OK" retry 10 3 bash -c \
      "curl -fsS -u admin:\"\$GRAFANA_ADMIN_PASSWORD\" http://localhost:19300/api/datasources/uid/$uid/health | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin)[\"status\"]==\"OK\" else 1)'"
  done
}

verify_health
for p in "${profiles[@]}"; do
  if declare -F "verify_$p" >/dev/null; then "verify_$p"; else current="$p"; fail "verify_$p が未定義"; fi
done

printf '\n%s: PASS %d / FAIL %d\n' "profile=${profiles[*]}" "$passed" "$failed"
[[ "$failed" -eq 0 ]]
