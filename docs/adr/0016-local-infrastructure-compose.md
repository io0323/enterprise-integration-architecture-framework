
- 2026-10-09: P06 ⑥b で、部分の再同期のロール `eiaf_resync`(`postgres/init/40-reconcile.sh`。signal 表の INSERT だけ。パスワードは `make env` の `LEGACY_RESYNC_DB_PASSWORD`)を加えた。前のボリュームでは `make clean` が要る。`legacy-order-acl` は照合の後に、上限 100 件の範囲でずれを自動で取り直す(ADR-0027 §6)。
- 2026-10-09: P07 ④a で `saga` profile を加えた(注文 Saga の参加者。inventory-migrate・inventory-service。kafka-connect・exporter・schema-publish・kafka-topics も saga で起動する)。参加者の `mem_limit` は 256m(開発機で core + order + saga を同時に動かすため。ADR-0029)。CI の infra の verify にも saga を加えた。
- 2026-10-09: P07 ④b で saga profile に payment-service・shipping-service(と各 migrate)を加えた。`mem_limit` は inventory と同じ 256m(実測は各 170MiB 前後)。
