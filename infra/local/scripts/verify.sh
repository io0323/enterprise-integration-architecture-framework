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

# ------------------------------------------------------------------ 共通: healthy
verify_health() {
  current="health"
  local services
  services="$("${compose[@]}" config --services)"
  for svc in $services; do
    local state
    state="$("${compose[@]}" ps -a --format '{{.State}}/{{.Health}}/{{.ExitCode}}' "$svc" 2>/dev/null | head -1)"
    if [[ " ${ONESHOT_SERVICES[*]} " == *" $svc "* ]]; then
      # 初期化して終了するコンテナは、終了コード 0 で止まっていれば成功
      if [[ "$state" == "exited//0" ]]; then pass "$svc: 初期化が完了 (exit 0)"; else fail "$svc: ${state:-コンテナなし}"; fi
    elif [[ "$state" == running/healthy/* ]]; then
      pass "$svc: running/healthy"
    else
      fail "$svc: ${state:-コンテナなし}"
    fi
  done
}
# 初期化して終了するコンテナ(healthy ではなく終了コードで判定する)
ONESHOT_SERVICES=(seaweedfs-init)

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

  # Prometheus: ミドルウェア自身のメトリクスの scrape 先がすべて up
  check "Prometheus: scrape 先がすべて up" retry 10 3 bash -c \
    "curl -fsS http://localhost:19090/api/v1/targets | python3 -c 'import json,sys; t=json.load(sys.stdin)[\"data\"][\"activeTargets\"]; sys.exit(0 if t and all(x[\"health\"]==\"up\" for x in t) else 1)'"

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
    -e AUDIT_ACCESS_KEY="$AUDIT_S3_ACCESS_KEY" -e AUDIT_SECRET_KEY="$AUDIT_S3_SECRET_KEY" \
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

verify_health
for p in "${profiles[@]}"; do
  if declare -F "verify_$p" >/dev/null; then "verify_$p"; else current="$p"; fail "verify_$p が未定義"; fi
done

printf '\n%s: PASS %d / FAIL %d\n' "profile=${profiles[*]}" "$passed" "$failed"
[[ "$failed" -eq 0 ]]
