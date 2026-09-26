#!/usr/bin/env bash
# Debezium(cdc profile)が論理レプリケーションで使うユーザー(Framework 8)。
# REPLICATION と、CDC の対象になり得る DB への CONNECT だけを与える。テーブルの SELECT とパブリケーションは、
# 対象テーブルの所有者(各サービスのユーザー)がマイグレーションで付与・作成する(P06)。
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v password="$DEBEZIUM_DB_PASSWORD" <<'SQL'
CREATE ROLE debezium LOGIN REPLICATION PASSWORD :'password';
GRANT CONNECT ON DATABASE order_service TO debezium;
GRANT CONNECT ON DATABASE legacy_sim TO debezium;
SQL
