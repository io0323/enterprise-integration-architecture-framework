-- DBA の作業: 照合(legacy-order-acl の reconcile。ADR-0027)のための読み取りの権限。レガシーのアプリと表の定義は変えない。
-- ロール(${reconcileRole})と、その接続数・問い合わせの時間の上限は、クラスタの初期化(infra/local/postgres/init/40-reconcile.sh)で作る
GRANT SELECT ON t_juchu TO ${reconcileRole};
