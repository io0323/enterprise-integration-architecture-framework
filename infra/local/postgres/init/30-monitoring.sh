#!/usr/bin/env bash
# postgres-exporter(cdc / order profile)が接続するユーザー(P06 ④)。
# 組み込みのロール pg_monitor(統計情報と設定の読み取り。pg_replication_slots・pg_settings を含む)だけを与え、表のデータは読めない。
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v password="$POSTGRES_EXPORTER_DB_PASSWORD" <<'SQL'
CREATE ROLE postgres_exporter LOGIN PASSWORD :'password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION IN ROLE pg_monitor;
SQL
