#!/usr/bin/env bash
# ローカル基盤の検証(P03 DoD の証跡)。`make verify PROFILE=<name>` から呼ぶ。
# 1. 指定 profile(+ core)の全サービスのコンテナが healthy であること
# 2. profile ごとの機能の疎通(下の verify_<profile> 関数)
# 秘密情報は .env から読むが、出力には出さない。
set -euo pipefail
# 補足: kafka コンテナで CLI を実行するときは KAFKA_HEAP_OPTS を上書きし、ブローカーと同じヒープを確保させない

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
# compose の配列はパスに空白を含みうるので、bash -c の文字列に展開せず、次の関数から配列のまま使う
kafka_cli() { "${compose[@]}" exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka "$@"; }
psql_super() { "${compose[@]}" exec -T postgres psql -U postgres -tAc "$1"; }
equals() { [[ "$("${@:2}")" == "$1" ]]; } # equals <期待値> <コマンド...>
consume() { # consume <トピック> <件数> <タイムアウト ms>(読めた内容を出力する。失敗しても空で返す)
  kafka_cli /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic "$1" \
    --from-beginning --max-messages "$2" --timeout-ms "$3" 2>/dev/null || true
}

# Keycloak の client credentials で、sales.order:* のスコープのアクセストークンを取る(失敗したら空)
# client_token [クライアント ID] [シークレット](既定は eiaf-e2e)
client_token() {
  local id="${1:-eiaf-e2e}" secret="${2:-$EIAF_E2E_CLIENT_SECRET}"
  curl -fsS -X POST http://localhost:19180/realms/eiaf/protocol/openid-connect/token \
    -d grant_type=client_credentials -d client_id="$id" --data-urlencode "client_secret=$secret" \
    --data-urlencode 'scope=sales.order:read sales.order:write' | json 'd["access_token"]' 2>/dev/null || true
}

# 応答のヘッダの値(curl -D - の出力から。大文字小文字を区別しない)
header_value() { awk -v name="$(tr '[:upper:]' '[:lower:]' <<<"$1")" -F': ' 'tolower($1) == name { sub(/\r$/, "", $2); print $2; exit }'; }

# 1 回だけ動いて終わるコンテナ(終了コード 0 で終わっていれば正常)
oneshot_services=" order-migrate schema-publish legacy-migrate kafka-topics "

# ------------------------------------------------------------------ 共通: healthy
verify_health() {
  current="health"
  local services
  services="$("${compose[@]}" config --services)"
  for svc in $services; do
    local state
    state="$("${compose[@]}" ps -a --format '{{.State}}/{{.Health}}/{{.ExitCode}}' "$svc" 2>/dev/null | head -1)"
    if [[ "$oneshot_services" == *" $svc "* ]]; then
      if [[ "$state" == exited/*/0 ]]; then pass "$svc: 終了コード 0 で終わった"; else fail "$svc: ${state:-コンテナなし}"; fi
    elif [[ "$state" == running/healthy/* ]]; then
      pass "$svc: running/healthy"
    else
      fail "$svc: ${state:-コンテナなし}"
    fi
  done
}

# ------------------------------------------------------------------ core
verify_core() {
  current="core"
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
  check "PostgreSQL: wal_level=logical" equals logical psql_super 'show wal_level'
  # アプリ用のロール({name}_app。ADR-0017)。ロールの追加より前に作ったボリュームには無いため、make clean が必要
  local db app_roles_ok=true
  for db in order_service inventory_service payment_service shipping_service legacy_sim batch_etl; do
    if ! equals 1 psql_super "select count(*) from pg_roles where rolname = '${db}_app' and not rolsuper and not rolcreaterole and not rolcreatedb"; then
      fail "PostgreSQL: アプリ用のロール ${db}_app がない(ボリュームが古い場合は make clean → make up)"
      app_roles_ok=false
    fi
  done
  if $app_roles_ok; then pass "PostgreSQL: サービス別 DB のアプリ用のロール({name}_app)がある"; fi
  check "PostgreSQL: order_service_app で order_service に接続できる" \
    "${compose[@]}" exec -T -e PGPASSWORD="$ORDER_APP_DB_PASSWORD" postgres psql -h 127.0.0.1 -U order_service_app -d order_service -tAc 'select 1'
  if "${compose[@]}" exec -T -e PGPASSWORD="$ORDER_APP_DB_PASSWORD" postgres psql -h 127.0.0.1 -U order_service_app -d inventory_service -tAc 'select 1' >/dev/null 2>&1; then
    fail "PostgreSQL: order_service_app が inventory_service に接続できてしまう"
  else
    pass "PostgreSQL: order_service_app は inventory_service に接続できない"
  fi

  # Kafka: 自動作成が無効で、明示的に作ったトピックで produce / consume できる
  local topic="eiaf.verify.probed.v1" kbin=/opt/kafka/bin
  "${compose[@]}" exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka $kbin/kafka-topics.sh --bootstrap-server kafka:9092 --delete --topic "$topic" >/dev/null 2>&1 || true
  check "Kafka: トピックを作成できる" \
    "${compose[@]}" exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka $kbin/kafka-topics.sh --bootstrap-server kafka:9092 --create --topic "$topic" --partitions 1 --replication-factor 1
  check "Kafka: ホストのリスナー(localhost:19092)から produce できる" \
    kafka_cli $kbin/kafka-console-producer.sh --bootstrap-server localhost:19092 --topic "$topic" <<<"verify-$$"
  if grep -q -- "verify-$$" <<<"$(consume "$topic" 1 15000)"; then pass "Kafka: consume できる"; else fail "Kafka: consume できる"; fi
  "${compose[@]}" exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka $kbin/kafka-topics.sh --bootstrap-server kafka:9092 --delete --topic "$topic" >/dev/null 2>&1 || true
  local produce_output
  produce_output="$("${compose[@]}" exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka $kbin/kafka-console-producer.sh --bootstrap-server kafka:9092 \
    --topic eiaf.verify.undeclared.v1 --command-property max.block.ms=5000 <<<"x" 2>&1 || true)"
  if grep -q -i -E "not present in metadata|UNKNOWN_TOPIC" <<<"$produce_output" &&
    ! "${compose[@]}" exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka $kbin/kafka-topics.sh --bootstrap-server kafka:9092 --list | grep -q -x eiaf.verify.undeclared.v1; then
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

  # Prometheus: ミドルウェア自身のメトリクスの scrape 先がすべて up(CDC の監視の 3 つは cdc / order profile の検査で確かめる)
  check "Prometheus: scrape 先がすべて up(CDC の監視を除く)" retry 10 3 bash -c \
    "curl -fsS http://localhost:19090/api/v1/targets | python3 -c 'import json,sys; t=[x for x in json.load(sys.stdin)[\"data\"][\"activeTargets\"] if x[\"labels\"][\"job\"] not in (\"postgres-exporter\",\"kafka-exporter\",\"kafka-connect\")]; sys.exit(0 if t and all(x[\"health\"]==\"up\" for x in t) else 1)'"

  # Grafana: provisioning した 3 つのデータソースが疎通する
  for uid in prometheus tempo loki; do
    check "Grafana: データソース $uid の health が OK" retry 10 3 bash -c \
      "curl -fsS -u admin:\"\$GRAFANA_ADMIN_PASSWORD\" http://localhost:19300/api/datasources/uid/$uid/health | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin)[\"status\"]==\"OK\" else 1)'"
  done
}

# ------------------------------------------------------------------ cdc
verify_cdc() {
  current="cdc"
  # 実行ごとに名前を変える(同じ名前だと Connect に残ったオフセットから再開し、スナップショットが走らない)
  local run_id connect=http://localhost:19083
  run_id="$(date +%s)"
  local connector="eiaf-verify-cdc-$run_id" prefix="eiaf-verify-$run_id" slot="eiaf_verify_$run_id"
  local topic="$prefix.public.verify_probe"
  local kbin=/opt/kafka/bin
  psql_as() { # psql_as <user> <password> <db> <sql>
    "${compose[@]}" exec -T -e PGPASSWORD="$2" postgres psql -h 127.0.0.1 -v ON_ERROR_STOP=1 -U "$1" -d "$3" -tAc "$4"
  }
  cleanup_cdc() {
    # コネクタを止めてからオフセットを消し、コネクタを削除する(Kafka Connect 3.6+ の offsets API)
    if curl -fsS -o /dev/null "$connect/connectors/$connector" 2>/dev/null; then
      curl -sS -o /dev/null -X PUT "$connect/connectors/$connector/stop" || true
      retry 10 1 bash -c "curl -fsS $connect/connectors/$connector/status | grep -q STOPPED" || true
      curl -sS -o /dev/null -X DELETE "$connect/connectors/$connector/offsets" || true
      curl -sS -o /dev/null -X DELETE "$connect/connectors/$connector" || true
    fi
    psql_as legacy_sim "$LEGACY_SIM_DB_PASSWORD" legacy_sim \
      "DROP PUBLICATION IF EXISTS eiaf_verify; DROP TABLE IF EXISTS verify_probe;" >/dev/null 2>&1 || true
    "${compose[@]}" exec -T postgres psql -U postgres -tAc \
      "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name LIKE 'eiaf_verify%' AND NOT active" >/dev/null 2>&1 || true
    "${compose[@]}" exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka $kbin/kafka-topics.sh --bootstrap-server kafka:9092 --delete --topic "$topic" >/dev/null 2>&1 || true
  }

  check "Kafka Connect: PostgresConnector(Debezium)が使える" bash -c \
    "curl -fsS $connect/connector-plugins | python3 -c 'import json,sys; sys.exit(0 if any(p[\"class\"]==\"io.debezium.connector.postgresql.PostgresConnector\" for p in json.load(sys.stdin)) else 1)'"
  check "Kafka Connect: Apicurio の AvroConverter が使える" bash -c \
    "curl -fsS '$connect/connector-plugins?connectorsOnly=false' | python3 -c 'import json,sys; sys.exit(0 if any(p[\"class\"]==\"io.apicurio.registry.utils.converter.AvroConverter\" for p in json.load(sys.stdin)) else 1)'"
  check "PostgreSQL: debezium ユーザーが REPLICATION を持つ" \
    equals t psql_super "select rolreplication from pg_roles where rolname = 'debezium'"

  # 一時テーブルの変更を Debezium で Kafka に流す(スナップショット 1 件 + INSERT 1 件)
  cleanup_cdc
  check "CDC: 一時テーブルとパブリケーションを作り、debezium に SELECT を付与できる" psql_as legacy_sim "$LEGACY_SIM_DB_PASSWORD" legacy_sim \
    "CREATE TABLE verify_probe (id int PRIMARY KEY, note text); INSERT INTO verify_probe VALUES (1, 'snapshot');
     GRANT SELECT ON verify_probe TO debezium; CREATE PUBLICATION eiaf_verify FOR TABLE verify_probe;"
  local config
  config="$(python3 -c 'import json,sys; print(json.dumps({"name":sys.argv[1],"config":{
    "connector.class":"io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname":"postgres","database.port":"5432","database.user":"debezium","database.password":"${env:DEBEZIUM_DB_PASSWORD}",
    "database.dbname":"legacy_sim","topic.prefix":sys.argv[3],"table.include.list":"public.verify_probe",
    "plugin.name":"pgoutput","publication.name":"eiaf_verify","publication.autocreate.mode":"disabled",
    "slot.name":sys.argv[4],"slot.drop.on.stop":"true",
    "key.converter":"org.apache.kafka.connect.json.JsonConverter","key.converter.schemas.enable":"false",
    "value.converter":"org.apache.kafka.connect.json.JsonConverter","value.converter.schemas.enable":"false",
    "topic.creation.default.replication.factor":"1","topic.creation.default.partitions":"1"}}))' "$connector" unused "$prefix" "$slot")"
  check "CDC: Debezium のコネクタを登録できる" curl -fsS -o /dev/null -X POST "$connect/connectors" -H 'Content-Type: application/json' -d "$config"
  check "CDC: コネクタとタスクが RUNNING" retry 30 2 bash -c \
    "curl -fsS $connect/connectors/$connector/status | python3 -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d[\"connector\"][\"state\"]==\"RUNNING\" and d[\"tasks\"] and all(t[\"state\"]==\"RUNNING\" for t in d[\"tasks\"]) else 1)'"
  # パスワードは EnvVarConfigProvider で参照させ、平文が config API と内部トピックに残らないこと(Framework 12.2)
  local stored
  stored="$(curl -fsS "$connect/connectors/$connector/config" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("database.password",""))' || true)"
  if [[ "$stored" == '${env:DEBEZIUM_DB_PASSWORD}' ]] && ! grep -q -F -- "$DEBEZIUM_DB_PASSWORD" <<<"$stored"; then
    pass "CDC: config API はパスワードを \${env:...} の参照のまま返し、平文を含まない"
  else
    fail "CDC: config API のパスワードが参照になっていない"
  fi
  local configs_topic
  configs_topic="$("${compose[@]}" exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka $kbin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic _connect.configs \
    --from-beginning --timeout-ms 5000 2>/dev/null || true)"
  if [[ -n "$configs_topic" ]] && ! grep -q -F -- "$DEBEZIUM_DB_PASSWORD" <<<"$configs_topic"; then
    pass "CDC: _connect.configs トピックに平文のパスワードが含まれない"
  else
    fail "CDC: _connect.configs トピックを読めないか、平文のパスワードが含まれる"
  fi
  psql_as legacy_sim "$LEGACY_SIM_DB_PASSWORD" legacy_sim "INSERT INTO verify_probe VALUES (2, 'streamed')" >/dev/null 2>&1 || true
  local events=0
  for ((i = 0; i < 15; i++)); do
    events="$(grep -c '"op"' <<<"$(consume "$topic" 2 10000)" || true)"
    [[ "$events" == 2 ]] && break
    sleep 2
  done
  if [[ "$events" == 2 ]]; then
    pass "CDC: スナップショット(op=r)と INSERT(op=c)の 2 件が {prefix}.public.verify_probe に届く"
  else
    fail "CDC: $topic に届いた変更イベントが 2 件でない ('$events')"
  fi
  cleanup_cdc
  verify_legacy_cdc
  verify_cdc_monitoring
}

# レガシーの受注表の CDC(P06 ⑤。ADR-0026): legacy-sim → コネクタ legacy-juchu → 生の CDC のトピック(Avro。Apicurio のグループ cdc-raw)
verify_legacy_cdc() {
  local connect=http://localhost:19083 topic=_cdc.legacy.public.t_juchu
  legacy_db() { "${compose[@]}" exec -T postgres psql -U postgres -d legacy_sim -tAc "$1"; }
  legacy_change_arrived() { # legacy_change_arrived <トピック> <受注番号>
    kafka_cli /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic "$1" --from-beginning \
      --timeout-ms 5000 --property print.key=true 2>/dev/null | grep -a -q -F "$2"
  }
  check "CDC(legacy): コネクタ legacy-juchu とタスクが RUNNING" bash -c \
    "curl -fsS $connect/connectors/legacy-juchu/status | python3 -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d[\"connector\"][\"state\"]==\"RUNNING\" and d[\"tasks\"] and all(t[\"state\"]==\"RUNNING\" for t in d[\"tasks\"]) else 1)'"
  check "CDC(legacy): レプリケーションスロット legacy_juchu が使用中" equals "t" \
    psql_super "select active from pg_replication_slots where slot_name = 'legacy_juchu'"
  # DBA の設定の範囲(ADR-0026): 受注表は SELECT だけ、signal 表は Snapshot の印を書いて消すための SELECT・INSERT・DELETE
  check "CDC(legacy): debezium の権限は t_juchu の SELECT と signal 表の SELECT・INSERT・DELETE だけ" \
    equals "eiaf_cdc.debezium_signal:DELETE,eiaf_cdc.debezium_signal:INSERT,eiaf_cdc.debezium_signal:SELECT,public.t_juchu:SELECT" \
    legacy_db "select string_agg(g, ',' order by g) from (select table_schema || '.' || table_name || ':' || privilege_type as g from information_schema.role_table_grants where grantee = 'debezium') t"
  check "CDC(legacy): 受注表は REPLICA IDENTITY FULL(削除の変更に受注番号が載る)" equals "f" \
    legacy_db "select relreplident from pg_class where oid = 'public.t_juchu'::regclass"

  # レガシーのアプリとして受注を 1 件登録し、受注番号のキーで生のトピックに届く(Avro の文字列は UTF-8 のまま入るため、バイト列で探す)
  local number
  number="$("${compose[@]}" --profile legacy-sim-cli run --rm --build --no-deps legacy-sim simulate seed 1 2>/dev/null | grep -E '^J[0-9]{9}$' | tail -1 || true)"
  if [[ -n "$number" ]] && retry 30 2 legacy_change_arrived "$topic" "$number"; then
    pass "CDC(legacy): 登録した受注($number)が $topic に届く"
  else
    fail "CDC(legacy): 登録した受注(${number:-登録できない})が $topic に届かない"
  fi
  check "CDC(legacy): 生の CDC のスキーマが Apicurio のグループ cdc-raw にある(Converter の自動登録)" bash -c \
    "curl -fsS 'http://localhost:19081/apis/registry/v3/groups/cdc-raw/artifacts?limit=100' | python3 -c 'import json,sys; ids={a[\"artifactId\"] for a in json.load(sys.stdin)[\"artifacts\"]}; sys.exit(0 if {\"$topic-key\",\"$topic-value\"} <= ids else 1)'"

  # Anti-Corruption Layer(P06 ⑤b。ADR-0026): 登録した受注が、注文番号のキーで整形済みのトピックに届く。変換できない受注は DLQ に入る
  local out=sales.legacy-order.changed.v1 dlq=_cdc.legacy.public.t_juchu.dlq anomaly
  if [[ -n "$number" ]] && retry 30 2 legacy_change_arrived "$out" "$number"; then
    pass "ACL(legacy): 登録した受注($number)が $out に届く"
  else
    fail "ACL(legacy): 登録した受注(${number:-登録できない})が $out に届かない"
  fi
  # DLQ のキーと値はバイト列のまま(Converter の Avro。contentId のバイトに改行の値が入ると、1 件が複数行に割れる)。
  # そのため、テキストのヘッダだけを出し、原因が UNKNOWN_STATUS_CODE のレコードの件数が、変換できない受注の登録の後に増えたことを見る
  dlq_unknown_status() {
    kafka_cli /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic "$dlq" --from-beginning --timeout-ms 5000 \
      --property print.headers=true --property print.key=false --property print.value=false 2>/dev/null |
      grep -a -c 'eiaf.dlq.reason:UNKNOWN_STATUS_CODE' || true
  }
  dlq_grew() { [[ "$(dlq_unknown_status)" -gt "$1" ]]; }
  local before
  before="$(dlq_unknown_status)"
  anomaly="$("${compose[@]}" --profile legacy-sim-cli run --rm --build --no-deps legacy-sim simulate anomaly unknown-status 2>/dev/null | grep -E '^J[0-9]{9}$' | tail -1 || true)"
  if [[ -n "$anomaly" ]] && retry 30 2 dlq_grew "${before:-0}"; then
    pass "ACL(legacy): 変換できない受注($anomaly)が $dlq に原因のヘッダ(UNKNOWN_STATUS_CODE)付きで入る"
  else
    fail "ACL(legacy): 変換できない受注(${anomaly:-登録できない})が $dlq に入らない"
  fi
  topic_config() { # topic_config <トピック>(cleanup.policy・retention.ms・delete.retention.ms の値)
    kafka_cli /opt/kafka/bin/kafka-configs.sh --bootstrap-server kafka:9092 --describe --entity-type topics --entity-name "$1" 2>/dev/null |
      awk '$1 ~ /^(cleanup\.policy|delete\.retention\.ms|retention\.ms)=/ { print $1 }' | sort | tr '\n' ' '
  }
  check "ACL(legacy): DLQ のトピックは delete・保持 7 日(topics.conf)" \
    equals "cleanup.policy=delete retention.ms=604800000 " topic_config "$dlq"
  check "ACL(legacy): 出力のトピックは compact・tombstone を 1 日残す(topics.conf)" \
    equals "cleanup.policy=compact delete.retention.ms=86400000 " topic_config "$out"
  check "ACL(legacy): Prometheus が ACL のメトリクス(eia_acl_records_total)を収集している" retry 20 3 bash -c \
    "[[ -n \"\$(curl -fsS -G http://localhost:19090/api/v1/query --data-urlencode 'query=eia_acl_records_total{job=\"legacy-order-acl\"}' | python3 -c 'import json,sys; print(json.load(sys.stdin)[\"data\"][\"result\"] or \"\")')\" ]]"
  check "ACL(legacy): アラートのルール(acl.rules.yml の 3 つ)を読み込んでいる" bash -c \
    "curl -fsS http://localhost:19090/api/v1/rules | python3 -c 'import json,sys; n={r[\"name\"] for g in json.load(sys.stdin)[\"data\"][\"groups\"] for r in g[\"rules\"]}; sys.exit(0 if {\"LegacyAclDeadLetters\",\"LegacyAclLagHigh\",\"LegacyAclDown\"} <= n else 1)'"
  # 照合(P06 ⑥。ADR-0027): 読み取り専用のロールの上限、定期の照合の成功とずれがないこと、手動の照合(Runbook)
  check "照合(legacy): eiaf_reconcile は接続数 4・statement_timeout 30s・idle_in_transaction 60s・読み取り専用" \
    equals "4|statement_timeout=30s,idle_in_transaction_session_timeout=60s,default_transaction_read_only=on" \
    psql_super "select rolconnlimit || '|' || array_to_string(rolconfig, ',', '') from pg_roles where rolname = 'eiaf_reconcile'"
  check "照合(legacy): eiaf_reconcile の表の権限は t_juchu の SELECT だけ" equals "public.t_juchu:SELECT" \
    legacy_db "select string_agg(table_schema || '.' || table_name || ':' || privilege_type, ',') from information_schema.role_table_grants where grantee = 'eiaf_reconcile'"
  # 定期の照合(2 分ごと)が、直近の 6 分以内に成功し、ずれが 0 件(変換できない受注は既知の差で、ずれに数えない)
  reconcile_ok() {
    [[ "$(prom_value 'time() - eia_reconcile_last_success_seconds{job="legacy-order-acl"} < 360')" != "" ]] &&
      [[ "$(prom_value 'sum(eia_reconcile_drift_keys{job="legacy-order-acl"})')" == "0" ]] &&
      [[ "$(prom_value 'sum(eia_reconcile_checks_total{job="legacy-order-acl",outcome="consistent"})')" != "" ]]
  }
  check "照合(legacy): 定期の照合が成功し、ずれが 0 件(Prometheus)" retry 40 6 reconcile_ok
  local manual
  manual="$("${compose[@]}" run --rm --no-deps legacy-order-acl reconcile 2>/dev/null; echo "exit=$?")"
  if grep -q '^exit=0$' <<<"$manual" && grep -q '^position=.* drift=0 unconvertible=[1-9]' <<<"$manual"; then
    pass "照合(legacy): 手動の照合(legacy-order-acl reconcile)が一致で終わり、変換できない受注を別に数える"
  else
    fail "照合(legacy): 手動の照合が一致で終わらない ($(grep -E '^(position|exit)=' <<<"$manual" | tr '\n' ' '))"
  fi
  check "照合(legacy): アラートのルール(reconcile.rules.yml の 3 つ)を読み込んでいる" bash -c \
    "curl -fsS http://localhost:19090/api/v1/rules | python3 -c 'import json,sys; n={r[\"name\"] for g in json.load(sys.stdin)[\"data\"][\"groups\"] for r in g[\"rules\"]}; sys.exit(0 if {\"LegacyReconcileStale\",\"LegacyReconcileDrift\",\"LegacyReconcileDriftOverLimit\"} <= n else 1)'"

  local dash result
  dash="$(curl -fsS -u "admin:$GRAFANA_ADMIN_PASSWORD" http://localhost:19300/api/dashboards/uid/eiaf-cdc-legacy || true)"
  if [[ "$(json 'd["meta"]["folderTitle"] + "/" + d["dashboard"]["title"]' <<<"$dash" 2>/dev/null)" == "EIAF/CDC — Legacy" ]]; then
    pass "Grafana: ダッシュボード CDC — Legacy を読み込んでいる"
    # 読み直しとアラートは、平常時には空でよい
    result="$(dashboard_panels_return_data "$dash" "読み直し(一時的な失敗。1 時間の回数)" "レガシーの CDC のアラート(firing / pending)" "照合の失敗(1 時間の回数)")"
    if [[ "$result" == ok ]]; then pass "Grafana: CDC — Legacy の全パネルの式がデータを返す"; else fail "Grafana: CDC — Legacy のパネル: $result"; fi
  else
    fail "Grafana: ダッシュボード eiaf-cdc-legacy を読み込めない"
  fi
}

# ------------------------------------------------------------------ iot
verify_iot() {
  current="iot"
  local topic="eiaf/verify/$$" message="verify-$$" received
  # 購読を先に始め、QoS 1 で 1 件 publish して受け取れること(ホストのポート 19883 経由はコンテナ内の 1883 と同じリスナー)
  received="$(
    "${compose[@]}" exec -T mosquitto sh -c \
      "mosquitto_sub -h 127.0.0.1 -u \"\$MQTT_USERNAME\" -P \"\$MQTT_PASSWORD\" -t '$topic' -q 1 -C 1 -W 10 & sleep 1;
       mosquitto_pub -h 127.0.0.1 -u \"\$MQTT_USERNAME\" -P \"\$MQTT_PASSWORD\" -t '$topic' -q 1 -m '$message'; wait" 2>&1 || true
  )"
  if [[ "$received" == *"$message"* ]]; then pass "Mosquitto: 認証つきで QoS 1 の publish / subscribe ができる"; else fail "Mosquitto: publish した値を受け取れない ('$received')"; fi

  if "${compose[@]}" exec -T mosquitto mosquitto_pub -h 127.0.0.1 -t "$topic" -m anonymous >/dev/null 2>&1; then
    fail "Mosquitto: 匿名接続が許可されている"
  else
    pass "Mosquitto: 匿名接続を拒否する"
  fi
  if "${compose[@]}" exec -T mosquitto mosquitto_pub -h 127.0.0.1 -u "$MQTT_USERNAME" -P wrong-password -t "$topic" -m x >/dev/null 2>&1; then
    fail "Mosquitto: 誤ったパスワードで接続できる"
  else
    pass "Mosquitto: 誤ったパスワードを拒否する"
  fi
  check "Mosquitto: ホストのポート 19883 で待ち受けている" bash -c "exec 3<>/dev/tcp/127.0.0.1/19883"
}

# ------------------------------------------------------------------ file / b2b
# S3 互換ストレージ(SeaweedFS)の互換性検査(scripts/s3-compat.sh を AWS CLI のコンテナで実行する)
verify_s3() {
  local output line
  output="$(docker run --rm --network eiaf \
    -e AWS_ACCESS_KEY_ID="$S3_ACCESS_KEY" -e AWS_SECRET_ACCESS_KEY="$S3_SECRET_KEY" \
    -e RUN_ID="$(date +%s)-$$" -e S3_ENDPOINT=http://seaweedfs:8333 -e S3_PUBLIC_ENDPOINT=http://localhost:19333 \
    -v "$here/scripts/s3-compat.sh:/s3-compat.sh:ro" --entrypoint bash "$AWS_CLI_IMAGE" /s3-compat.sh 2>&1 || true)"
  while IFS= read -r line; do
    case "$line" in
      "OK "*) pass "S3: ${line#OK }" ;;
      "NG "*) fail "S3: ${line#NG }" ;;
    esac
  done <<<"$output"
  grep -q -E '^(OK|NG) ' <<<"$output" || fail "S3: 検査スクリプトを実行できない ($(tail -1 <<<"$output"))"
  check "S3: ホストのポート 19333 で応答する" curl -fsS -o /dev/null http://localhost:19333/healthz
  verify_audit_bucket
}

# 監査のアンカー用のバケットと、audit の資格情報の範囲(scripts/s3-audit.sh。ADR-0017)
verify_audit_bucket() {
  local output line
  output="$(docker run --rm --network eiaf \
    -e ADMIN_ACCESS_KEY="$S3_ACCESS_KEY" -e ADMIN_SECRET_KEY="$S3_SECRET_KEY" \
    -e ORDER_ACCESS_KEY="$ORDER_AUDIT_S3_ACCESS_KEY" -e ORDER_SECRET_KEY="$ORDER_AUDIT_S3_SECRET_KEY" \
    -e VERIFY_ACCESS_KEY="$AUDIT_VERIFY_S3_ACCESS_KEY" -e VERIFY_SECRET_KEY="$AUDIT_VERIFY_S3_SECRET_KEY" \
    -e RUN_ID="$(date +%s)-$$" -e S3_ENDPOINT=http://seaweedfs:8333 \
    -v "$here/scripts/s3-audit.sh:/s3-audit.sh:ro" --entrypoint bash "$AWS_CLI_IMAGE" /s3-audit.sh 2>&1 || true)"
  while IFS= read -r line; do
    case "$line" in
      "OK "*) pass "S3 audit: ${line#OK }" ;;
      "NG "*) fail "S3 audit: ${line#NG }" ;;
    esac
  done <<<"$output"
  grep -q -E '^(OK|NG) ' <<<"$output" || fail "S3 audit: 検査スクリプトを実行できない ($(tail -1 <<<"$output"))"
}

# SFTP: 公開鍵認証で接続し、一時名で置いて正式名にリネーム(Framework 9)→ 取得 → 削除できる。パスワード認証は拒否する
verify_sftp() { # verify_sftp <サービス名> <ホストのポート> <ユーザー> <鍵ファイル名>
  local service="$1" port="$2" user="$3" key="$here/secrets/$4" name="verify-$$.csv" work
  local ssh_opts=(-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=10)
  work="$(mktemp -d)"
  printf 'id,value\n1,%s\n' "$$" >"$work/$name"
  if sftp "${ssh_opts[@]}" -i "$key" -P "$port" -b - "$user@127.0.0.1" >/dev/null 2>&1 <<EOF
put $work/$name inbox/$name.part
rename inbox/$name.part inbox/$name
get inbox/$name $work/downloaded.csv
rm inbox/$name
EOF
  then
    if cmp -s "$work/$name" "$work/downloaded.csv"; then
      pass "$service: 公開鍵認証で put(一時名)→ rename → get → rm ができる"
    else
      fail "$service: 取得した内容が一致しない"
    fi
  else
    fail "$service: 公開鍵認証での SFTP 操作に失敗した"
  fi
  if sftp "${ssh_opts[@]}" -o PreferredAuthentications=password -o PubkeyAuthentication=no -o BatchMode=yes \
    -P "$port" -b - "$user@127.0.0.1" >/dev/null 2>&1 <<<"ls"; then
    fail "$service: パスワード認証で接続できてしまう"
  else
    pass "$service: パスワード認証を拒否する"
  fi
  rm -rf "$work"
}

verify_file() {
  current="file"
  verify_s3
  verify_sftp sftp 19222 eiaf-file sftp-file
}

verify_b2b() {
  current="b2b"
  verify_s3
  verify_sftp sftp-b2b 19223 partner01 sftp-b2b
  # 取引先の SFTP に社内向けの鍵では入れない(利用者と鍵を分けている)
  if sftp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o BatchMode=yes \
    -i "$here/secrets/sftp-file" -P 19223 -b - partner01@127.0.0.1 >/dev/null 2>&1 <<<"ls"; then
    fail "sftp-b2b: 社内向け(sftp-file)の鍵で接続できてしまう"
  else
    pass "sftp-b2b: 社内向け(sftp-file)の鍵を拒否する"
  fi
}

# ------------------------------------------------------------------ chaos
verify_chaos() {
  current="chaos"
  local api=http://localhost:19474 kbin=/opt/kafka/bin topic="eiaf.verify.toxic.v1"
  # Toxiproxy のネットワーク名前空間で Kafka のクライアントを動かす。localhost:19094 がホストから見た場合と同じく Toxiproxy に当たる
  kafka_via_proxy() { docker run --rm -i --network container:eiaf-toxiproxy-1 --entrypoint "$1" "$KAFKA_IMAGE" "${@:2}"; }
  elapsed_ms() { python3 -c 'import subprocess,sys,time; t=time.time(); r=subprocess.run(sys.argv[1:],capture_output=True); print(int((time.time()-t)*1000) if r.returncode==0 else -1)' "$@"; }
  toxic_cleanup() {
    for proxy in kafka-host kafka-internal postgres; do
      curl -sS -o /dev/null -X POST "$api/proxies/$proxy" -H 'Content-Type: application/json' -d '{"enabled":true}' || true
      curl -sS -o /dev/null -X DELETE "$api/proxies/$proxy/toxics/verify_latency" || true
    done
  }
  toxic_cleanup

  check "Toxiproxy: kafka-host / kafka-internal / postgres の proxy が有効" bash -c \
    "curl -fsS $api/proxies | python3 -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if all(d.get(n,{}).get(\"enabled\") for n in [\"kafka-host\",\"kafka-internal\",\"postgres\"]) else 1)'"
  check "Toxiproxy: ホストのポート 19094(Kafka)と 19433(PostgreSQL)で待ち受けている" bash -c \
    "exec 3<>/dev/tcp/127.0.0.1/19094 && exec 4<>/dev/tcp/127.0.0.1/19433"

  # 専用リスナー: bootstrap も、メタデータで返る advertised(localhost:19094 / toxiproxy:19095)も Toxiproxy を指す
  local metadata
  metadata="$(kafka_via_proxy $kbin/kafka-broker-api-versions.sh --bootstrap-server localhost:19094 2>/dev/null | head -1 || true)"
  if [[ "$metadata" == "localhost:19094 "* ]]; then pass "Kafka: TOXI_HOST リスナーの advertised が localhost:19094(Toxiproxy)"; else fail "Kafka: TOXI_HOST の advertised が想定外 ('$metadata')"; fi
  metadata="$(kafka_via_proxy $kbin/kafka-broker-api-versions.sh --bootstrap-server toxiproxy:19095 2>/dev/null | head -1 || true)"
  if [[ "$metadata" == "toxiproxy:19095 "* ]]; then pass "Kafka: TOXI_INTERNAL リスナーの advertised が toxiproxy:19095"; else fail "Kafka: TOXI_INTERNAL の advertised が想定外 ('$metadata')"; fi

  "${compose[@]}" exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka $kbin/kafka-topics.sh --bootstrap-server kafka:9092 --delete --topic "$topic" >/dev/null 2>&1 || true
  "${compose[@]}" exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka $kbin/kafka-topics.sh --bootstrap-server kafka:9092 --create --topic "$topic" --partitions 1 --replication-factor 1 >/dev/null 2>&1 || true
  check "Kafka: Toxiproxy 経由(localhost:19094)で produce / consume できる" bash -c "
    echo toxic-$$ | docker run --rm -i --network container:eiaf-toxiproxy-1 --entrypoint $kbin/kafka-console-producer.sh $KAFKA_IMAGE --bootstrap-server localhost:19094 --topic $topic &&
    docker run --rm --network container:eiaf-toxiproxy-1 --entrypoint $kbin/kafka-console-consumer.sh $KAFKA_IMAGE --bootstrap-server localhost:19094 --topic $topic --from-beginning --max-messages 1 --timeout-ms 20000 | grep -q toxic-$$"

  # latency の toxic: 応答が注入した遅延(下り 1500ms)以上に遅くなり、toxic を消すと戻る
  local base slow
  base="$(elapsed_ms docker run --rm --network container:eiaf-toxiproxy-1 --entrypoint $kbin/kafka-broker-api-versions.sh "$KAFKA_IMAGE" --bootstrap-server localhost:19094)"
  curl -fsS -o /dev/null -X POST "$api/proxies/kafka-host/toxics" -H 'Content-Type: application/json' \
    -d '{"name":"verify_latency","type":"latency","stream":"downstream","attributes":{"latency":1500,"jitter":0}}'
  slow="$(elapsed_ms docker run --rm --network container:eiaf-toxiproxy-1 --entrypoint $kbin/kafka-broker-api-versions.sh "$KAFKA_IMAGE" --bootstrap-server localhost:19094)"
  if [[ "$base" -ge 0 && "$slow" -ge $((base + 1500)) ]]; then
    pass "Toxiproxy: latency の toxic で Kafka の応答が遅くなる (${base}ms → ${slow}ms)"
  else
    fail "Toxiproxy: latency の toxic が効かない (${base}ms → ${slow}ms)"
  fi
  curl -sS -o /dev/null -X DELETE "$api/proxies/kafka-host/toxics/verify_latency" || true

  # proxy を無効にすると PostgreSQL に接続できなくなり、有効に戻すと接続できる
  local pg=(exec -T -e PGPASSWORD="$ORDER_DB_PASSWORD" -e PGCONNECT_TIMEOUT=5 postgres psql -h toxiproxy -p 19433 -U order_service -d order_service -tAc 'select 1')
  check "PostgreSQL: Toxiproxy 経由(toxiproxy:19433)で接続できる" "${compose[@]}" "${pg[@]}"
  curl -fsS -o /dev/null -X POST "$api/proxies/postgres" -H 'Content-Type: application/json' -d '{"enabled":false}'
  if "${compose[@]}" "${pg[@]}" >/dev/null 2>&1; then fail "Toxiproxy: proxy を無効にしても接続できる"; else pass "Toxiproxy: proxy を無効にすると PostgreSQL に接続できない"; fi
  curl -fsS -o /dev/null -X POST "$api/proxies/postgres" -H 'Content-Type: application/json' -d '{"enabled":true}'
  check "Toxiproxy: proxy を有効に戻すと接続できる" retry 5 1 "${compose[@]}" "${pg[@]}"

  toxic_cleanup
  "${compose[@]}" exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka $kbin/kafka-topics.sh --bootstrap-server kafka:9092 --delete --topic "$topic" >/dev/null 2>&1 || true
}

# ------------------------------------------------------------------ order(P05。ADR-0024)
verify_order() {
  current="order"
  local certs="$here/certs" orders="https://localhost:19443/v1/orders" token container
  token="$(client_token)"
  [[ -n "$token" ]] || fail "Keycloak: アクセストークンを取れない"
  container="$("${compose[@]}" ps -q order-service)"

  # mTLS なしの直接接続を拒否する(ROADMAP P05 の DoD)。API のポートはクライアント証明書を必須にし、SAN の許可の一覧を確かめる
  local tls=(curl -sS -o /dev/null --max-time 10 --cacert "$certs/ca.crt" -H "Authorization: Bearer $token")
  if "${tls[@]}" "$orders/ord-none" 2>/dev/null; then
    fail "order-service: クライアント証明書のない直接接続に応答した"
  else
    pass "order-service: クライアント証明書のない直接接続を拒否する"
  fi
  if curl -sS -o /dev/null --max-time 10 "http://localhost:19443/v1/orders/ord-none" 2>/dev/null; then
    fail "order-service: API のポートが平文の HTTP に応答した"
  else
    pass "order-service: API のポートは平文の HTTP に応答しない"
  fi
  # 同じ CA の証明書でも、SAN が許可の一覧(apisix)にないものは拒否する(order-service 自身の証明書で確かめる)
  if "${tls[@]}" --cert "$certs/order-service.crt" --key "$certs/order-service.key" "$orders/ord-none" 2>/dev/null; then
    fail "order-service: 許可の一覧にない SAN の証明書に応答した"
  else
    pass "order-service: 許可の一覧にない SAN(order-service)の証明書を拒否する"
  fi

  # ゲートウェイの証明書(SAN apisix)なら API に届く。POST と同じ Idempotency-Key の再送(サービスの段階。Gateway 経由は ⑤c)
  local gw=(curl -sS --max-time 15 --cacert "$certs/ca.crt" --cert "$certs/apisix.crt" --key "$certs/apisix.key" -H "Authorization: Bearer $token")
  local code
  code="$("${gw[@]}" -o /dev/null -w '%{http_code}' "$orders/ord-none" || true)"
  if [[ "$code" == 404 ]]; then
    pass "order-service: ゲートウェイの証明書(SAN apisix)なら API に届く(ない注文は 404)"
  else
    fail "order-service: ゲートウェイの証明書での応答が ${code:-なし}"
  fi
  local key="verify-$$-$(date +%s)" body first second posted_at
  posted_at="$(date +%s)"
  body='{"customerId":"cust-verify","lines":[{"productId":"prod-1","sku":"SKU-1","quantity":1,"unitPrice":{"amount":"1000","currency":"JPY"}}],"shippingAddress":{"countryCode":"JP","postalCode":"100-0001","city":"Chiyoda","line1":"1-1"}}'
  first="$("${gw[@]}" -o - -w '\n%{http_code}' -X POST "$orders" -H "Idempotency-Key: $key" -H 'Content-Type: application/json' -d "$body" || true)"
  second="$("${gw[@]}" -D - -o - -X POST "$orders" -H "Idempotency-Key: $key" -H 'Content-Type: application/json' -d "$body" || true)"
  if [[ "$(tail -n1 <<<"$first")" == 201 ]]; then pass "order-service: POST /v1/orders が 201"; else fail "order-service: POST /v1/orders が $(tail -n1 <<<"$first")"; fi
  if grep -q -i '^idempotent-replayed: true' <<<"$second" && [[ "$(tail -n1 <<<"$second")" == "$(sed '$d' <<<"$first" | tail -n1)" ]]; then
    pass "order-service: 同じ Idempotency-Key の再送は同じ本文と Idempotent-Replayed: true"
  else
    fail "order-service: 同じ Idempotency-Key の再送の応答が想定外"
  fi

  # ヘルスチェックは平文のポート(8081)で、コンテナの外には公開しない
  check "order-service: ヘルスチェック(平文の 8081)が UP" \
    "${compose[@]}" exec -T order-service /probe/bin/wget -q -O /dev/null http://127.0.0.1:8081/health/ready
  if [[ -z "$("${compose[@]}" port order-service 8081 2>/dev/null || true)" ]]; then
    pass "order-service: ヘルスチェックのポートはホストに公開しない"
  else
    fail "order-service: ヘルスチェックのポートがホストに公開されている"
  fi

  # serve は所有者のパスワードを持たない(ADR-0024 §2)。値は出さず、変数の名前だけを見る
  if docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container" | cut -d= -f1 | grep -q -E '^ORDER_DB_PASSWORD(_FILE)?$'; then
    fail "order-service: serve の環境に所有者のパスワードがある"
  else
    pass "order-service: serve の環境に所有者のパスワードがない"
  fi
  # root で動かさない(ADR-0024 §7)
  local user
  user="$(docker inspect --format '{{.Config.User}}' "$container")"
  if [[ -n "$user" && "${user%%:*}" != 0 && "${user%%:*}" != root ]]; then
    pass "order-service: root 以外の利用者で動く (user=$user)"
  else
    fail "order-service: root で動いている (user='$user')"
  fi

  verify_order_cdc "$(sed '$d' <<<"$first" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' 2>/dev/null || true)" "${gw[@]}"
  verify_cdc_monitoring
  verify_order_cdc_alerts "${gw[@]}"
  verify_audit_anchor "$posted_at"
  verify_gateway
  verify_dashboard
}

# 注文のイベントの発行(Outbox → Debezium → Kafka。ADR-0007・P06 ③b)
# verify_order_cdc <上で POST した注文 ID> <ゲートウェイの証明書で API を呼ぶ curl...>
verify_order_cdc() {
  local order_id="$1" connect=http://localhost:19083 topic=sales.order.created.v1
  shift
  local gw=("$@")
  check "CDC(order): コネクタ order-outbox とタスクが RUNNING" bash -c \
    "curl -fsS $connect/connectors/order-outbox/status | python3 -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d[\"connector\"][\"state\"]==\"RUNNING\" and d[\"tasks\"] and all(t[\"state\"]==\"RUNNING\" for t in d[\"tasks\"]) else 1)'"

  # debezium のロールは REPLICATION と、Outbox の表の SELECT だけ(superuser ではない。業務の表は読めない)
  check "CDC(order): debezium は REPLICATION を持ち、superuser ではない" equals "ok" \
    psql_super "select case when rolreplication and not rolsuper then 'ok' else 'ng' end from pg_roles where rolname = 'debezium'"
  check "CDC(order): debezium の order_service の表の権限は outbox.outbox の SELECT だけ" equals "outbox.outbox:SELECT" \
    "${compose[@]}" exec -T postgres psql -U postgres -d order_service -tAc \
    "select string_agg(table_schema || '.' || table_name || ':' || privilege_type, ',' order by 1) from information_schema.role_table_grants where grantee = 'debezium'"
  check "CDC(order): debezium は業務の表(orders)を読めない" equals "f" \
    "${compose[@]}" exec -T postgres psql -U postgres -d order_service -tAc "select has_table_privilege('debezium', 'public.orders', 'SELECT')"

  # スロットは使用中で、保持する WAL に上限がある(コネクタが止まってもディスクを使い切らない)
  check "CDC(order): レプリケーションスロット order_outbox が使用中" equals "t" \
    psql_super "select active from pg_replication_slots where slot_name = 'order_outbox'"
  check "CDC(order): max_slot_wal_keep_size が 1GB" equals "1GB" psql_super "show max_slot_wal_keep_size"

  # 上で POST した注文のイベントが、注文 ID のキーと CloudEvents のヘッダで届く
  if [[ -n "$order_id" ]] && retry 30 2 order_event_arrived "$topic" "$order_id"; then
    pass "CDC(order): POST した注文のイベントが $topic に届く(キー = 注文 ID、ce_type・ce_specversion・traceparent のヘッダ付き)"
  else
    fail "CDC(order): POST した注文($order_id)のイベントが $topic に届かない"
  fi

  # Kafka を止めて(compose stop)注文を作り、再開(start)の後に欠けずに届く(再起動を伴う止まり方。docker pause は統合テスト)
  local key="verify-stop-$$-$(date +%s)" body created stopped_id
  body='{"customerId":"cust-verify","lines":[{"productId":"prod-1","sku":"SKU-1","quantity":1,"unitPrice":{"amount":"1000","currency":"JPY"}}],"shippingAddress":{"countryCode":"JP","postalCode":"100-0001","city":"Chiyoda","line1":"1-1"}}'
  "${compose[@]}" stop kafka >/dev/null 2>&1
  created="$("${gw[@]}" -o - -w '\n%{http_code}' -X POST "https://localhost:19443/v1/orders" -H "Idempotency-Key: $key" -H 'Content-Type: application/json' -d "$body" || true)"
  "${compose[@]}" start kafka >/dev/null 2>&1
  if [[ "$(tail -n1 <<<"$created")" == 201 ]]; then
    pass "CDC(order): Kafka を止めている間も、注文の作成は 201(Outbox に書くだけ)"
  else
    fail "CDC(order): Kafka を止めている間の注文の作成が $(tail -n1 <<<"$created")"
  fi
  stopped_id="$(sed '$d' <<<"$created" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' 2>/dev/null || true)"
  retry 60 2 kafka_cli /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server kafka:9092 >/dev/null 2>&1 || true
  if [[ -n "$stopped_id" ]] && retry 60 2 order_event_arrived "$topic" "$stopped_id"; then
    pass "CDC(order): Kafka を再開した後に、止めている間に作った注文のイベントが欠けずに届く"
  else
    fail "CDC(order): Kafka を再開した後に、止めている間の注文($stopped_id)のイベントが届かない"
  fi
  check "CDC(order): Kafka の再開の後もコネクタのタスクが RUNNING" retry 30 2 bash -c \
    "curl -fsS $connect/connectors/order-outbox/status | python3 -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d[\"tasks\"] and all(t[\"state\"]==\"RUNNING\" for t in d[\"tasks\"]) else 1)'"
  check "CDC(order): Outbox の表に行が残らない" equals "0" \
    "${compose[@]}" exec -T postgres psql -U postgres -d order_service -tAc "select count(*) from outbox.outbox"
}

# CDC の監視(P06 ④。prometheus/rules/cdc.rules.yml・docs/runbooks/cdc-outbox-lag.md)。cdc と order の profile で共通の検査
prom_value() { # prom_value <PromQL>(最初の結果の値。なければ空)
  curl -fsS -G http://localhost:19090/api/v1/query --data-urlencode "query=$1" |
    python3 -c 'import json,sys; r=json.load(sys.stdin)["data"]["result"]; print(r[0]["value"][1] if r else "")' 2>/dev/null || true
}
cdc_alert_state() { # cdc_alert_state <アラート名>(firing / pending / なければ空)
  curl -fsS http://localhost:19090/api/v1/alerts |
    python3 -c 'import json,sys; a=[x["state"] for x in json.load(sys.stdin)["data"]["alerts"] if x["labels"]["alertname"]==sys.argv[1]]; print("firing" if "firing" in a else (a[0] if a else ""))' "$1" 2>/dev/null || true
}
verify_cdc_monitoring() {
  local job
  for job in postgres-exporter kafka-exporter kafka-connect; do
    check "CDC の監視: Prometheus が $job から収集できる" retry 20 3 bash -c \
      "curl -fsS http://localhost:19090/api/v1/targets | python3 -c 'import json,sys; t=[x for x in json.load(sys.stdin)[\"data\"][\"activeTargets\"] if x[\"labels\"][\"job\"]==\"$job\"]; sys.exit(0 if t and all(x[\"health\"]==\"up\" for x in t) else 1)'"
  done
  check "CDC の監視: アラートのルール(cdc.rules.yml の 6 つ)を読み込んでいる" bash -c \
    "curl -fsS http://localhost:19090/api/v1/rules | python3 -c 'import json,sys; n={r[\"name\"] for g in json.load(sys.stdin)[\"data\"][\"groups\"] for r in g[\"rules\"]}; sys.exit(0 if {\"CdcSlotWalAtRisk\",\"CdcSlotLost\",\"CdcSlotLagHigh\",\"CdcOutboxSlotMissing\",\"CdcConnectorDown\",\"CdcMonitoringDown\"} <= n else 1)'"
  # 割合の閾値の基準(上限)を、Prometheus が PostgreSQL の設定から得ている
  check "CDC の監視: 上限 max_slot_wal_keep_size(1GiB)を pg_settings から収集している" equals "1073741824" \
    prom_value 'pg_settings_max_slot_wal_keep_size_bytes'
}

# order の Outbox のスロットの監視とアラート(コネクタを止めると CdcConnectorDown が firing し、再開すると解消する)
# verify_order_cdc_alerts <ゲートウェイの証明書で API を呼ぶ curl...>
verify_order_cdc_alerts() {
  local connect=http://localhost:19083 gw=("$@") alert
  check "CDC の監視: order_outbox の wal_status・safe_wal_size・遅延を収集している" retry 20 3 bash -c \
    "[[ -n \"\$(curl -fsS -G http://localhost:19090/api/v1/query --data-urlencode 'query=pg_replication_slots_safe_wal_size_bytes{slot_name=\"order_outbox\"} and on(slot_name) pg_replication_slots_wal_status{slot_name=\"order_outbox\",wal_status=\"reserved\"} and on(slot_name) pg_replication_slots_pg_wal_lsn_diff' | python3 -c 'import json,sys; print(json.load(sys.stdin)[\"data\"][\"result\"] or \"\")')\" ]]"
  for alert in CdcSlotWalAtRisk CdcSlotLost CdcSlotLagHigh CdcOutboxSlotMissing CdcConnectorDown CdcMonitoringDown; do
    if [[ "$(cdc_alert_state "$alert")" == firing ]]; then fail "CDC の監視: 平常時に $alert が firing している"; else pass "CDC の監視: 平常時に $alert は firing していない"; fi
  done

  # コネクタを止める(スロットは使われなくなり、WAL を保持し続ける)。止めている間の注文で、遅延の bytes が増える。
  # 止める要求は非同期で、止めるときに Debezium が最後に LSN を確定させる。Prometheus の値は 15 秒ごとの標本で、直前の Kafka の再起動の
  # 間の「使われていない」が残っていることがある。そのため、止まったことは Connect(STOPPED)と PostgreSQL(active = false)で直接確かめ、
  # 基準の遅延も PostgreSQL から直接読む。その後の注文で、Prometheus の遅延がそれを超えることを確かめる
  local before body key="verify-alert-$$-$(date +%s)" created
  curl -fsS -o /dev/null -X PUT "$connect/connectors/order-outbox/stop" || true
  retry 30 2 bash -c "curl -fsS $connect/connectors/order-outbox/status | grep -q '\"state\":\"STOPPED\"'" || true
  retry 30 2 equals f psql_super "select active from pg_replication_slots where slot_name = 'order_outbox'" || true
  before="$(psql_super "select pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)::bigint from pg_replication_slots where slot_name = 'order_outbox'")"
  body='{"customerId":"cust-verify","lines":[{"productId":"prod-1","sku":"SKU-1","quantity":1,"unitPrice":{"amount":"1000","currency":"JPY"}}],"shippingAddress":{"countryCode":"JP","postalCode":"100-0001","city":"Chiyoda","line1":"1-1"}}'
  created="$("${gw[@]}" -o /dev/null -w '%{http_code}' -X POST "https://localhost:19443/v1/orders" -H "Idempotency-Key: $key" -H 'Content-Type: application/json' -d "$body" || true)"
  [[ "$created" == 201 ]] || fail "CDC の監視: コネクタを止めている間の注文の作成が $created(201 でない)"
  if retry 20 3 bash -c "a=\$(curl -fsS -G http://localhost:19090/api/v1/query --data-urlencode 'query=pg_replication_slots_pg_wal_lsn_diff{slot_name=\"order_outbox\"}' | python3 -c 'import json,sys; r=json.load(sys.stdin)[\"data\"][\"result\"]; print(r[0][\"value\"][1] if r else 0)'); python3 -c 'import sys; sys.exit(0 if float(sys.argv[1]) > float(sys.argv[2] or 0) else 1)' \"\$a\" '$before'"; then
    pass "CDC の監視: コネクタを止めている間の注文で、スロットの遅延(bytes)が増える"
  else
    fail "CDC の監視: コネクタを止めても、スロットの遅延(bytes)が増えない(before=$before)"
  fi
  # for: 1m + 評価の間隔(15 秒)+ scrape の間隔(15 秒)
  if retry 60 3 bash -c "[[ \"\$(curl -fsS http://localhost:19090/api/v1/alerts | python3 -c 'import json,sys; print(\"firing\" if any(a[\"labels\"][\"alertname\"]==\"CdcConnectorDown\" and a[\"state\"]==\"firing\" for a in json.load(sys.stdin)[\"data\"][\"alerts\"]) else \"\")')\" == firing ]]"; then
    pass "CDC の監視: コネクタを止めると CdcConnectorDown(critical)が firing する"
  else
    fail "CDC の監視: コネクタを止めても CdcConnectorDown が firing しない"
  fi
  curl -fsS -o /dev/null -X PUT "$connect/connectors/order-outbox/resume" || true
  check "CDC の監視: 再開したコネクタとタスクが RUNNING" retry 30 2 bash -c \
    "curl -fsS $connect/connectors/order-outbox/status | python3 -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d[\"connector\"][\"state\"]==\"RUNNING\" and d[\"tasks\"] and all(t[\"state\"]==\"RUNNING\" for t in d[\"tasks\"]) else 1)'"
  if retry 40 3 bash -c "[[ -z \"\$(curl -fsS http://localhost:19090/api/v1/alerts | python3 -c 'import json,sys; print(\"x\" if any(a[\"labels\"][\"alertname\"]==\"CdcConnectorDown\" for a in json.load(sys.stdin)[\"data\"][\"alerts\"]) else \"\")')\" ]]"; then
    pass "CDC の監視: コネクタを再開すると CdcConnectorDown が解消する"
  else
    fail "CDC の監視: コネクタを再開しても CdcConnectorDown が解消しない"
  fi
}

# トピックに、キーが <注文 ID> で、契約のヘッダを持つイベントがあるか(order_event_arrived <トピック> <注文 ID>)
order_event_arrived() {
  local records
  records="$(kafka_cli /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic "$1" --from-beginning \
    --timeout-ms 10000 --property print.key=true --property print.headers=true --property print.value=false 2>/dev/null || true)"
  grep -F -- "$2" <<<"$records" | grep -q 'ce_type:sales.order.created' &&
    grep -F -- "$2" <<<"$records" | grep -q 'ce_specversion:1.0' &&
    grep -F -- "$2" <<<"$records" | grep -q 'traceparent:00-'
}

# 監査のアンカー(ADR-0017 §5)。order-service が 1 分ごと(compose の設定)に、前回のアンカーからの差分を検証して保存する。
# 上の POST の記録を含むアンカーの保存を待ち、全体の検査(make audit-verify)が OK で、アンカーがあることを確かめる
verify_audit_anchor() { # verify_audit_anchor <POST した時刻(UNIX 時刻)>
  local since="$1" prom="http://localhost:19090/api/v1/query" published="" output status=0
  promql() { curl -fsS --get "$prom" --data-urlencode "query=$1" | json 'd["data"]["result"][0]["value"][1] if d["data"]["result"] else ""'; }
  for _ in $(seq 1 18); do
    published="$(promql 'max(eia_audit_anchor_last_published_seconds{job="order-service"})' 2>/dev/null || true)"
    [[ -n "$published" ]] && python3 -c "import sys; sys.exit(0 if float('$published') >= $since else 1)" && break
    published=""
    sleep 10
  done
  if [[ -n "$published" ]]; then
    pass "監査: order-service が POST の後にアンカーを保存した(eia.audit.anchor.last_published)"
  else
    fail "監査: POST の後 180 秒のうちに、アンカーの保存がメトリクスに出ない"
  fi
  # アラートの候補(最後に検査が成功してから間隔の 2 倍を超えた)の式。ラベルが一致して 1 系列になり、条件には当たらないこと
  local margin
  margin="$(promql 'time() - eia_audit_anchor_last_success_seconds{job="order-service"} - 2 * eia_audit_anchor_interval_seconds{job="order-service"}' 2>/dev/null || true)"
  if [[ -n "$margin" ]] && python3 -c "import sys; sys.exit(0 if float('$margin') < 0 else 1)"; then
    pass "監査: アラートの候補の式(最後の検査の成功から間隔の 2 倍)を評価でき、条件に当たらない"
  else
    fail "監査: アラートの候補の式が想定外 (${margin:-系列なし})"
  fi
  output="$("$here/scripts/audit-anchored.sh" order 2>&1)" || status=$?
  if [[ "$status" == 0 ]]; then
    pass "監査: make audit-verify SERVICE=order が OK(アンカーあり。$(grep -o -E 'records=[0-9]+ head_seq=[0-9]+ anchor_versions=[0-9]+' <<<"$output"))"
  else
    fail "監査: make audit-verify SERVICE=order が OK でないか、アンカーがない ($(grep -E '^(NG|ERROR|注意)|^audit-anchored' <<<"$output" | head -3 | tr '\n' ' '))"
  fi
}

# ------------------------------------------------------------------ order: Gateway(APISIX → order-service。ADR-0023)
verify_gateway() {
  current="gateway"
  local url="http://localhost:19080/sales/v1/orders" token token_b out code
  token="$(client_token)"
  token_b="$(client_token eiaf-e2e-b "$EIAF_E2E_B_CLIENT_SECRET")"
  [[ -n "$token_b" ]] || fail "Keycloak: eiaf-e2e-b のトークンを取れない(このクライアントより前に作ったボリュームなら make clean → make up)"

  # JWT の検証(ADR-0023 §2)。ゲートウェイ自身の 401 は Problem Details
  out="$(curl -sS -D - -o - "$url/ord-none" || true)"
  if [[ "$(head -1 <<<"$out")" == *" 401 "* ]] && [[ "$(header_value content-type <<<"$out")" == application/problem+json* ]] &&
    grep -q 'problems/unauthorized' <<<"$out"; then
    pass "Gateway: トークンがなければ 401(Problem Details)"
  else
    fail "Gateway: トークンなしの応答が想定外 ($(head -1 <<<"$out" | tr -d '\r'))"
  fi
  local tampered
  tampered="$(python3 -c 'import sys; h,p,s=sys.argv[1].split("."); m=len(s)//2; print(h+"."+p+"."+s[:m]+("A" if s[m]!="A" else "B")+s[m+1:])' "$token")"
  code="$(curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $tampered" "$url/ord-none" || true)"
  if [[ "$code" == 401 ]]; then pass "Gateway: 署名を改ざんしたトークンは 401"; else fail "Gateway: 署名を改ざんしたトークンが $code"; fi

  # 公開パスの書き換え(/sales/v1 → /v1)と mTLS で order-service に届く。X-Correlation-Id は受け取った値を返す
  local cid="verify-gw-$$"
  out="$(curl -sS -D - -o - -H "Authorization: Bearer $token" -H "X-Correlation-Id: $cid" "$url/ord-none" || true)"
  if [[ "$(head -1 <<<"$out")" == *" 404 "* ]] && grep -q 'problems/not-found' <<<"$out"; then
    pass "Gateway: /sales/v1/orders/{id} を order-service の /v1/orders/{id} に送る(mTLS。ない注文は 404)"
  else
    fail "Gateway: order-service に届かない ($(head -1 <<<"$out" | tr -d '\r'))"
  fi
  if [[ "$(header_value x-correlation-id <<<"$out")" == "$cid" ]]; then pass "Gateway: 受け取った X-Correlation-Id を返す"; else fail "Gateway: X-Correlation-Id が返らない"; fi
  if [[ -n "$(curl -sS -D - -o /dev/null -H "Authorization: Bearer $token" "$url/ord-none" | header_value x-correlation-id)" ]]; then
    pass "Gateway: X-Correlation-Id がなければ付ける"
  else
    fail "Gateway: X-Correlation-Id を付けない"
  fi

  # 同じ Idempotency-Key の再送で同じ応答(ROADMAP P05 の DoD。Gateway 経由)
  local key="verify-gw-$$-$(date +%s)" body first second
  body='{"customerId":"cust-gw","lines":[{"productId":"prod-1","sku":"SKU-1","quantity":1,"unitPrice":{"amount":"1000","currency":"JPY"}}],"shippingAddress":{"countryCode":"JP","postalCode":"100-0001","city":"Chiyoda","line1":"1-1"}}'
  first="$(curl -sS -o - -w '\n%{http_code}' -X POST "$url" -H "Authorization: Bearer $token" -H "Idempotency-Key: $key" -H 'Content-Type: application/json' -d "$body" || true)"
  second="$(curl -sS -D - -o - -X POST "$url" -H "Authorization: Bearer $token" -H "Idempotency-Key: $key" -H 'Content-Type: application/json' -d "$body" || true)"
  if [[ "$(tail -n1 <<<"$first")" == 201 && "$(header_value idempotent-replayed <<<"$second")" == true &&
    "$(tail -n1 <<<"$second")" == "$(sed '$d' <<<"$first" | tail -n1)" ]]; then
    pass "Gateway: POST は 201。同じ Idempotency-Key の再送は同じ本文と Idempotent-Replayed: true"
  else
    fail "Gateway: POST / 再送の応答が想定外 ($(tail -n1 <<<"$first"))"
  fi

  # 外部の traceparent を捨て、ゲートウェイでトレースを始める(ADR-0023 §4)。Tempo に apisix の span が、
  # X-Correlation-Id の属性つきで記録され、order-service の span の親になる(#8 の APISIX の OTel の確認)
  local sent_trace cid2="verify-trace-$$-$(date +%s)" found
  sent_trace="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
  curl -sS -o /dev/null -H "Authorization: Bearer $token" -H "X-Correlation-Id: $cid2" \
    -H "traceparent: 00-$sent_trace-$(python3 -c 'import secrets; print(secrets.token_hex(8))')-01" "$url/ord-none" || true
  found=""
  for _ in $(seq 1 20); do
    found="$(curl -sS -G http://localhost:19320/api/search \
      --data-urlencode "q={ resource.service.name = \"apisix\" && span.x-correlation-id = \"$cid2\" }" --data-urlencode limit=5 |
      json '",".join(t["traceID"] for t in d.get("traces",[]))' 2>/dev/null || true)"
    [[ -n "$found" ]] && break
    sleep 3
  done
  if [[ -n "$found" && "$found" != *"$sent_trace"* ]]; then
    pass "Gateway: 外部の traceparent を使わず、新しいトレースを始める(Tempo に apisix の span と X-Correlation-Id)"
  else
    fail "Gateway: Tempo の apisix の span が想定外 (found='${found}')"
  fi
  local chain
  chain="$(curl -sS "http://localhost:19320/api/traces/${found%%,*}" | python3 -c '
import base64,json,sys
d=json.load(sys.stdin); spans={}
for b in d.get("batches",[]):
  svc=[a["value"].get("stringValue") for a in b["resource"]["attributes"] if a["key"]=="service.name"][0]
  for ss in b.get("scopeSpans",[]):
    for s in ss.get("spans",[]): spans[s["spanId"]]=(svc,s.get("parentSpanId",""))
roots=[i for i,(svc,p) in spans.items() if svc=="apisix" and not p]
children=[i for i,(svc,p) in spans.items() if svc=="order-service" and p in roots]
print("ok" if roots and children else "ng")' 2>/dev/null || true)"
  if [[ "$chain" == ok ]]; then pass "Gateway: order-service の span の親は apisix の span"; else fail "Gateway: order-service の span の親が apisix の span ではない"; fi

  # クライアント(azp)ごとの Rate Limit。偽の X-Eiaf-Client-Id / X-Userinfo を付けても、ほかのクライアントの枠は減らない(ADR-0023 §5)
  remaining() { curl -sS -D - -o /dev/null "$@" "$url/ord-none" | header_value x-ratelimit-remaining; }
  local b_before b_after a_last forged_userinfo
  forged_userinfo="$(printf '{"azp":"eiaf-e2e-b"}' | base64)"
  b_before="$(remaining -H "Authorization: Bearer $token_b")"
  for _ in 1 2 3; do
    a_last="$(remaining -H "Authorization: Bearer $token" -H "X-Eiaf-Client-Id: eiaf-e2e-b" -H "X-Userinfo: $forged_userinfo")"
  done
  b_after="$(remaining -H "Authorization: Bearer $token_b")"
  if [[ -n "$b_before" && -n "$b_after" && "$b_after" -eq $((b_before - 1)) ]]; then
    pass "Gateway: 偽の X-Eiaf-Client-Id / X-Userinfo を付けた要求は、ほかのクライアント(eiaf-e2e-b)の枠を減らさない ($b_before → $b_after)"
  else
    fail "Gateway: ほかのクライアントの枠が想定外に変わった ('$b_before' → '$b_after')"
  fi
  [[ -n "$a_last" ]] && pass "Gateway: 偽のヘッダの要求は、送ったクライアント自身の枠で数える (残り $a_last)" || fail "Gateway: X-RateLimit-Remaining がない"

  # 枠を超えたら 429 + Retry-After(整数の秒)+ Problem Details。ほかのクライアントは影響を受けない
  local limited=""
  for _ in $(seq 1 80); do
    out="$(curl -sS -D - -o - -H "Authorization: Bearer $token" "$url/ord-none" || true)"
    if [[ "$(head -1 <<<"$out")" == *" 429 "* ]]; then limited="$out"; break; fi
  done
  local retry
  retry="$(header_value retry-after <<<"$limited")"
  if [[ -n "$limited" && "$retry" =~ ^[1-9][0-9]*$ && "$(header_value content-type <<<"$limited")" == application/problem+json* ]] &&
    grep -q 'problems/rate-limited' <<<"$limited"; then
    pass "Gateway: 枠を超えると 429 + Retry-After ($retry 秒) + Problem Details(rate-limited)"
  else
    fail "Gateway: 429 / Retry-After が想定外 (Retry-After='${retry}')"
  fi
  code="$(curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token_b" "$url/ord-none" || true)"
  if [[ "$code" == 404 ]]; then pass "Gateway: ほかのクライアント(eiaf-e2e-b)は 429 にならない"; else fail "Gateway: eiaf-e2e-b が $code"; fi
}

# ------------------------------------------------------------------ order: RED のダッシュボード(Grafana。P05 ⑥a)
verify_dashboard() {
  current="dashboard"
  local dash
  dash="$(curl -fsS -u "admin:$GRAFANA_ADMIN_PASSWORD" http://localhost:19300/api/dashboards/uid/eiaf-order-red || true)"
  if [[ "$(json 'd["meta"]["folderTitle"] + "/" + d["dashboard"]["title"]' <<<"$dash" 2>/dev/null)" == "EIAF/Order API — RED" ]]; then
    pass "Grafana: ダッシュボード Order API — RED(フォルダ EIAF)を provisioning で読み込んでいる"
  else
    fail "Grafana: ダッシュボード eiaf-order-red を読み込めない"
    return
  fi

  # caller_deadline のパネルは、order-service が依存先を呼ばない P05 では空なので、合成の値(service.name=eiaf-verify)を
  # 2 点送り、メトリクスの名前(OTLP → Prometheus の変換)と式が正しいことを確かめる
  local now prev point
  now="$(python3 -c 'import time; print(time.time_ns())')"
  prev=$((now - 30000000000))
  point() { # point <値> <時刻(ns)>
    printf '{"asInt":"%s","startTimeUnixNano":"%s","timeUnixNano":"%s","attributes":[{"key":"kind","value":{"stringValue":"caller_deadline"}},{"key":"eia.dependency.name","value":{"stringValue":"verify-dependency"}}]}' \
      "$1" "$((prev - 1000000000))" "$2"
  }
  check "OTel Collector: 合成の eia.resilience.timeouts(caller_deadline)を受け付ける" curl -fsS -X POST http://localhost:19318/v1/metrics -H 'Content-Type: application/json' \
    -d "{\"resourceMetrics\":[{\"resource\":{\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":\"eiaf-verify\"}}]},\"scopeMetrics\":[{\"metrics\":[{\"name\":\"eia.resilience.timeouts\",\"unit\":\"{call}\",\"sum\":{\"aggregationTemporality\":2,\"isMonotonic\":true,\"dataPoints\":[$(point 1 "$prev"),$(point 3 "$now")]}}]}]}]}"

  # すべてのパネルの式を Prometheus で評価する。式がエラーにならず、データを返すこと
  # (エラーの種類別と、監査の追記の失敗のパネルは、失敗がなければ空でよい)。メトリクスの送信は 10 秒ごとなので、少し待ってやり直す
  local result
  result="$(dashboard_panels_return_data "$dash" "Errors(error.type 別)" "監査の追記の失敗(種類別)")"
  if [[ "$result" == ok ]]; then
    pass "Grafana: RED のダッシュボードの全パネルの式が、Prometheus でデータを返す(Gateway・order-service・caller_deadline)"
  else
    fail "Grafana: パネルの式が想定外: $result"
  fi

  # CDC のダッシュボード(P06 ④)。アラートのパネルは平常時は空でよい
  dash="$(curl -fsS -u "admin:$GRAFANA_ADMIN_PASSWORD" http://localhost:19300/api/dashboards/uid/eiaf-cdc || true)"
  if [[ "$(json 'd["meta"]["folderTitle"] + "/" + d["dashboard"]["title"]' <<<"$dash" 2>/dev/null)" == "EIAF/CDC — Outbox" ]]; then
    pass "Grafana: ダッシュボード CDC — Outbox(フォルダ EIAF)を provisioning で読み込んでいる"
    result="$(dashboard_panels_return_data "$dash" "CDC のアラート(firing / pending)")"
    if [[ "$result" == ok ]]; then
      pass "Grafana: CDC のダッシュボードの全パネルの式が、Prometheus でデータを返す(スロット・コネクタ・発行の件数)"
    else
      fail "Grafana: CDC のパネルの式が想定外: $result"
    fi
  else
    fail "Grafana: ダッシュボード eiaf-cdc を読み込めない"
  fi
}

# ダッシュボード(Grafana の API の JSON)の全パネルの式を Prometheus で評価し、エラーがなくデータを返せば ok を出力する。
# 返さないパネルの名前を出力する。2 つめ以降の引数は、空でもよいパネルの名前。メトリクスの送信を待って 12 回までやり直す
dashboard_panels_return_data() { # dashboard_panels_return_data <ダッシュボードの JSON> [空でよいパネルの名前...]
  local dash="$1" result=""
  shift
  for _ in $(seq 1 12); do
    result="$(json 'json.dumps([[p["title"], t["expr"]] for p in d["dashboard"]["panels"] if p["type"] != "row" for t in p["targets"]])' <<<"$dash" |
      python3 -c '
import json, sys, urllib.error, urllib.parse, urllib.request
allowed_empty = tuple(sys.argv[1:])
bad = []
for title, expr in json.load(sys.stdin):
    q = urllib.parse.urlencode({"query": expr.replace("$__rate_interval", "2m")})
    try:
        with urllib.request.urlopen("http://localhost:19090/api/v1/query?" + q) as r:
            d = json.load(r)
    except urllib.error.HTTPError as e:  # 式の誤りは 400
        d = json.load(e)
    if d["status"] != "success":
        bad.append(title + "(式のエラー)")
    elif not d["data"]["result"] and title not in allowed_empty:
        bad.append(title + "(データなし)")
print("; ".join(sorted(set(bad))) if bad else "ok")
' "$@" 2>&1 || true)"
    [[ "$result" == ok ]] && break
    sleep 10
  done
  printf '%s' "$result"
}

verify_health
for p in "${profiles[@]}"; do
  if declare -F "verify_$p" >/dev/null; then "verify_$p"; else current="$p"; fail "verify_$p が未定義"; fi
done

printf '\n%s: PASS %d / FAIL %d\n' "profile=${profiles[*]}" "$passed" "$failed"
[[ "$failed" -eq 0 ]]
