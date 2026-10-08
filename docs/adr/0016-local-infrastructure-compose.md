
- 2026-10-09: P06 ⑥b で、部分の再同期のロール `eiaf_resync`(`postgres/init/40-reconcile.sh`。signal 表の INSERT だけ。パスワードは `make env` の `LEGACY_RESYNC_DB_PASSWORD`)を加えた。前のボリュームでは `make clean` が要る。`legacy-order-acl` は照合の後に、上限 100 件の範囲でずれを自動で取り直す(ADR-0027 §6)。
