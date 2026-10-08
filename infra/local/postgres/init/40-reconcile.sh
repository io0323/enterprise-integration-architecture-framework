#!/usr/bin/env bash
# レガシーの受注の照合(legacy-order-acl の reconcile。P06 ⑥。ADR-0027)がレガシーの DB を読むユーザー。
# 表の SELECT は、表の所有者(legacy_sim)が DBA の作業のマイグレーション(legacy-sim の V3)で付ける。
# レガシーの DB の負荷と VACUUM を守るため、ロールの設定で次を強制する(本番ではレプリカから読む。ADR-0027 §4):
# - CONNECTION LIMIT 2(照合は同時に 2 本まで使う: スナップショットの読み取りと、スロットの位置の確認)
# - statement_timeout 30s(1 回の問い合わせの上限)
# - idle_in_transaction_session_timeout 60s(トランザクションを開けたまま放置しない。照合は読み終えたらすぐに閉じる)
# - default_transaction_read_only on(書き込みはしない)
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v password="$LEGACY_RECONCILE_DB_PASSWORD" <<'SQL'
CREATE ROLE eiaf_reconcile LOGIN PASSWORD :'password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION CONNECTION LIMIT 2;
ALTER ROLE eiaf_reconcile SET statement_timeout = '30s';
ALTER ROLE eiaf_reconcile SET idle_in_transaction_session_timeout = '60s';
ALTER ROLE eiaf_reconcile SET default_transaction_read_only = on;
GRANT CONNECT ON DATABASE legacy_sim TO eiaf_reconcile;
SQL
