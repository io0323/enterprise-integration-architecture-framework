#!/usr/bin/env bash
# サービス別 DB とユーザーを作成する(Shared Database 禁止: CLAUDE.md §8)。
# 初回起動時(データディレクトリが空のとき)だけ docker-entrypoint.sh から実行される。
# 各ユーザーは自分の DB の所有者で、他の DB には CONNECT できない。
# サービスの DB には、所有者(マイグレーション用)とは別に、アプリが接続する {name}_app ロールを作る(ADR-0017)。
# アプリ用のロールはテーブルを所有せず、権限は各マイグレーションが付ける(例: 監査記録は INSERT と SELECT だけ)。
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

# アプリ用のロール。所有者ではないため、DDL・トリガーの無効化・権限の変更はできない
create_app_role() {
  local database="$1" password="$2"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
    -v role="${database}_app" -v database="$database" -v password="$password" <<'SQL'
CREATE ROLE :"role" LOGIN PASSWORD :'password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
GRANT CONNECT ON DATABASE :"database" TO :"role";
SQL
}

# name と対応するパスワードの環境変数(infra/local/.env)
create_database order_service "$ORDER_DB_PASSWORD"
create_database inventory_service "$INVENTORY_DB_PASSWORD"
create_database payment_service "$PAYMENT_DB_PASSWORD"
create_database shipping_service "$SHIPPING_DB_PASSWORD"
create_database legacy_sim "$LEGACY_SIM_DB_PASSWORD"
create_database batch_etl "$BATCH_ETL_DB_PASSWORD"
create_app_role order_service "$ORDER_APP_DB_PASSWORD"
create_app_role inventory_service "$INVENTORY_APP_DB_PASSWORD"
create_app_role payment_service "$PAYMENT_APP_DB_PASSWORD"
create_app_role shipping_service "$SHIPPING_APP_DB_PASSWORD"
create_app_role legacy_sim "$LEGACY_SIM_APP_DB_PASSWORD"
create_app_role batch_etl "$BATCH_ETL_APP_DB_PASSWORD"
create_database keycloak "$KEYCLOAK_DB_PASSWORD"
create_database apicurio "$APICURIO_DB_PASSWORD"
