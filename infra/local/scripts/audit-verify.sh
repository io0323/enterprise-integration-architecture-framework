#!/usr/bin/env bash
# 監査記録の改竄を検査する(ADR-0017)。`make audit-verify SERVICE=<name>` から呼ぶ。
# チェーンの検証とアンカー(S3 の eiaf-audit)との照合を tools/audit-verify で行い、その終了コードをそのまま返す:
#   0 = 改竄の疑いなし / 1 = 改竄の疑いあり / 2 = 検査を実行できない(設定・接続の失敗)
# 秘密情報は infra/local/.env から必要な値だけを取り出して渡し、出力には出さない。
set -euo pipefail

service="${1:-}"
here="$(cd "$(dirname "$0")/.." && pwd)"
root="$(cd "$here/../.." && pwd)"

# サービス名(アンカーのキー)→ DB 名と、アプリ用のロールのパスワードの変数(postgres/init/10-service-databases.sh)
case "$service" in
  order | inventory | payment | shipping) database="${service}_service" ;;
  batch-etl) database=batch_etl ;;
  legacy-sim) database=legacy_sim ;;
  *)
    echo "SERVICE を指定してください(order / inventory / payment / shipping / batch-etl / legacy-sim)。例: make audit-verify SERVICE=order" >&2
    exit 2
    ;;
esac
prefix="$(tr '[:lower:]' '[:upper:]' <<<"${database%_service}")"
password_var="${prefix}_APP_DB_PASSWORD"

if [[ ! -f "$here/.env" ]]; then
  echo "infra/local/.env がありません。make env を実行してください" >&2
  exit 2
fi
# .env を読み込むが、JVM には必要な値だけを渡す(ほかの秘密情報を子プロセスの環境に載せない)
# shellcheck disable=SC1091
source "$here/.env"
password="${!password_var:-}"
if [[ -z "$password" || -z "${AUDIT_S3_ACCESS_KEY:-}" || -z "${AUDIT_S3_SECRET_KEY:-}" ]]; then
  echo "infra/local/.env に $password_var / AUDIT_S3_ACCESS_KEY / AUDIT_S3_SECRET_KEY がありません。make env を実行してください" >&2
  exit 2
fi

# Gradle の出力は捨て、失敗したときだけ表示する
if ! build_log="$("$root/gradlew" -p "$root" -q :tools:audit-verify:installDist 2>&1)"; then
  echo "$build_log" >&2
  echo "tools/audit-verify をビルドできません(JAVA_HOME が JDK 21 以上を指しているか確かめてください)" >&2
  exit 2
fi

exec env -i PATH="$PATH" HOME="$HOME" JAVA_HOME="${JAVA_HOME:-}" \
  AUDIT_SERVICE="$service" \
  AUDIT_JDBC_URL="jdbc:postgresql://localhost:19432/$database" \
  AUDIT_DB_USER="${database}_app" \
  AUDIT_DB_PASSWORD="$password" \
  AUDIT_S3_ENDPOINT="http://localhost:19333" \
  AUDIT_S3_BUCKET="eiaf-audit" \
  AUDIT_S3_ACCESS_KEY="$AUDIT_S3_ACCESS_KEY" \
  AUDIT_S3_SECRET_KEY="$AUDIT_S3_SECRET_KEY" \
  AUDIT_MIN_RETENTION="${AUDIT_MIN_RETENTION:-P1D}" \
  "$root/tools/audit-verify/build/install/audit-verify/bin/audit-verify"
