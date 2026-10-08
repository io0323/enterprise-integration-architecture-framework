-- DBA の作業: 部分の再同期(legacy-order-acl。ADR-0027 §6)のための signal 表の INSERT。レガシーのアプリと表の定義は変えない。
-- ロール(${resyncRole})とその上限は、クラスタの初期化(infra/local/postgres/init/40-reconcile.sh)で作る。
-- Debezium の Snapshot の印(watermark)の読み書きは Debezium のロールが行い、このロールは指示の INSERT だけを持つ
GRANT USAGE ON SCHEMA eiaf_cdc TO ${resyncRole};
GRANT INSERT ON eiaf_cdc.debezium_signal TO ${resyncRole};
