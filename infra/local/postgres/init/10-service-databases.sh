#!/usr/bin/env bash
# サービス別 DB とユーザーを作成する(Shared Database 禁止: CLAUDE.md §8)。
# 初回起動時(データディレクトリが空のとき)だけ docker-entrypoint.sh から実行される。
# 各ユーザーは自分の DB の所有者で、他の DB には CONNECT できない。
set -euo pipefail

create_database() {
  local name="$1" password="$2"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
    -v name="$name" -v password="$password" <<'SQL'
CREATE ROLE :"name" LOGIN PASSWORD :'password';
CREATE DATABASE :"name" OWNER :"name";
REVOKE ALL ON DATABASE :"name" FROM PUBLIC;
SQL
}

# name と対応するパスワードの環境変数(infra/local/.env)
create_database order_service "$ORDER_DB_PASSWORD"
create_database inventory_service "$INVENTORY_DB_PASSWORD"
create_database payment_service "$PAYMENT_DB_PASSWORD"
create_database shipping_service "$SHIPPING_DB_PASSWORD"
create_database legacy_sim "$LEGACY_SIM_DB_PASSWORD"
create_database batch_etl "$BATCH_ETL_DB_PASSWORD"
create_database keycloak "$KEYCLOAK_DB_PASSWORD"
create_database apicurio "$APICURIO_DB_PASSWORD"
