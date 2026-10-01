# Runbook: 監査記録の改竄の検査(make audit-verify)

## 対象
- 監査記録(`audit.audit_log`)とアンカー(S3 の `eiaf-audit` の `anchors/{service}/{date}.json`)を定期的に検査するとき。
- `make audit-verify SERVICE=<name>` が終了コード 1(改竄の疑い)か 2(実行できない)を返したとき。
- order-service のアンカーの定期的な保存が、改竄の疑いで保存を拒否したとき、またはアラートの候補(最後に検査が成功してから間隔の 2 倍)に当たったとき(下の「アンカーの定期的な保存」)。

仕組みは ADR-0017 を参照。

## 実行
```bash
export JAVA_HOME=<JDK 21 以上>   # Apple Silicon では aarch64 の JDK
make up PROFILE=order             # PostgreSQL と SeaweedFS(file / b2b でもよい)
make audit-verify SERVICE=order   # order / inventory / payment / shipping / batch-etl / legacy-sim
echo $?                           # 0 = 改竄の疑いなし / 1 = 改竄の疑いあり / 2 = 実行できない
```

- 出力の 1 行目は、記録の件数・末尾の `seq`・アンカーの版の数。
- 最小の保持期間は `AUDIT_MIN_RETENTION`(ISO 8601。既定 `P1D`)で変えられる。アンカーを保存するときの保持期間と揃える。

## 終了コード 1(改竄の疑い)のとき
**記録とアンカーを変更しない。** 調べる前に証跡を保全する。

1. 出力の `NG <code>` の行を、そのまま記録する(出力には `seq` とアンカーのキーだけが出て、記録の中身は出ない)。
2. DB を保全する。`pg_dump -n audit` で `audit` スキーマを退避する(ローカルは `docker compose exec postgres pg_dump ...`)。
3. アンカーの全版の一覧を退避する。管理者の資格情報で `aws s3api list-object-versions --bucket eiaf-audit --prefix anchors/<service>/`(path-style)を実行する。
4. 検出の種類ごとに、次を確かめる。

| code | 意味 | 確かめること |
|---|---|---|
| `hash_mismatch` | 記録の列の値が書き換えられた | その `seq` の行。`audit_log` のトリガーが有効か(`SELECT tgname, tgenabled FROM pg_trigger WHERE tgrelid = 'audit.audit_log'::regclass`) |
| `broken_link` / `missing_seq` / `out_of_order` | 記録の削除・差し込み・入れ替え | 前後の `seq`。PostgreSQL のログで、所有者か superuser の `ALTER TABLE ... DISABLE TRIGGER` と `DELETE` / `UPDATE` |
| `malformed_record` | 列の値を解釈できない(details が文字列以外など) | その `seq` の行。制約(`CHECK` / `NOT NULL`)が外されていないか |
| `row_count_mismatch` | 表の件数と検証できた件数が合わない(検査は 1 つのスナップショットで行うので、検査中の追記では起きない) | `SELECT count(*), count(seq), count(hash) FROM audit.audit_log` と、`(seq, hash)` の重複(`GROUP BY seq, hash HAVING count(*) > 1`)。主キーや NOT NULL の制約が外されていないか |
| `unknown_canonical_version` | 直列化の方法がない版 | `canonical_version` の書き換えか、新しい版に対応する前の `tools/audit-verify` を使っていないか |
| `anchor_hash_mismatch` / `anchor_record_missing` | アンカーより前の記録の書き換え、またはアンカーより後の記録の削除 | アンカーの `seq` と、現在の末尾の `seq` |
| `anchor_delete_marker` | アンカーを消そうとした跡(削除マーカー) | 削除マーカーを作った資格情報。audit の資格情報はバケットポリシーで拒否されるため、管理者の資格情報の利用を調べる |
| `anchor_not_compliance` / `anchor_retention_too_short` | アンカーの保持の設定が弱い | 保存したアプリの設定(保持期間)。バケットの既定の保持設定が書き換えられていないか |
| `anchor_invalid` | アンカーの形式・キーの不一致・読めない | その版の内容。audit の資格情報で読めるか(バケットポリシー) |
| `head_not_reached` | (アンカーの保存の前の検証だけ)末尾の記録まで読めない | 末尾の行の `seq` と `hash` が NULL でないか、`seq` の重複。全体の検証(`make audit-verify`)の `row_count_mismatch` などとあわせて見る |

5. 本番では、セキュリティのインシデントとして扱い、所定の連絡先に報告する。ローカルでは、統合テストや手作業による改竄の再現でないかを確かめる。

## アンカーの定期的な保存(order-service。ADR-0017 §5)
order-service は、起動の直後と `ORDER_AUDIT_ANCHOR_INTERVAL`(既定 1 時間。ローカルは 1 分)ごとに、前回のアンカーからの差分を検証してアンカーを保存する。記録が増えていなければ保存しない。保存の前の検証は差分だけなので、**前回のアンカーより前の改竄は、このページの `make audit-verify` を定期的に行って見つける**。

- **ERROR `監査記録に改竄の疑いがあります(<code>)` と `改竄の疑いがあるため、監査のアンカーを保存しません`**
  - 保存を拒否した。解消するまで、毎回の検査で拒否し続ける(`eia.audit.anchor.checks` の `outcome=rejected`)。
  - 上の「終了コード 1 のとき」と同じ手順で保全し、調べる。`<code>` は上の表のとおり。`make audit-verify SERVICE=order` でチェーン全体も検査する。
  - `anchor_invalid`(最後に保存された版が解釈できない)のときは、起点にできないため保存しない。書込み用の資格情報(`eiaf-audit-order`)が漏れていないかを調べる(ADR-0017 §8)。
- **アラートの候補に当たった**(ダッシュボード「Order API — RED」の「アンカーの最後の検査の成功からの経過」が、破線の「間隔の 2 倍」を超えた。式は `time() - eia_audit_anchor_last_success_seconds > 2 * eia_audit_anchor_interval_seconds`)
  - 「アンカーの検査の結果(種類別)」で、`rejected`(上)か `error` かを見る。
  - `error` なら、order-service の WARN `監査のアンカーの検査を終えられませんでした ... (error.code=...)` を見る。`audit_storage_unavailable` は SeaweedFS(`make ps`・`make logs SERVICE=seaweedfs`)、`audit_storage_rejected` の `AccessDenied` は `ORDER_AUDIT_S3_*` と SeaweedFS の identity(`eiaf-audit-order`)の反映を確かめる。
  - どちらも出ていなければ、order-service が動いているか(`make ps`)を確かめる。
  - 注文がないだけでは当たらない(記録が増えていないことの確認も、検査の成功に数える)。「アンカーの最後の保存からの経過」が伸びるのは正常。
- **起動のときの WARN `監査のアンカーの保存は無効です`**: `ORDER_AUDIT_ANCHOR_ENABLED=false`。S3 のない統合テストのための設定で、本番とローカル基盤では使わない。

## 終了コード 2(実行できない)のとき
| 出力 | 対処 |
|---|---|
| `SERVICE を指定してください` | `make audit-verify SERVICE=order` のように指定する |
| `infra/local/.env ... がありません` | `make env` を実行する(不足している変数を追記する) |
| `PostgreSQL に接続できません(SQLSTATE 08001)` | `make up` で基盤を起動する。Docker Desktop を再起動した後は `make down && make up` |
| `SQLSTATE 28P01`(認証の失敗) | アプリ用のロール(`{name}_app`)がない古いボリューム。`make clean && make up`(ADR-0017 §2) |
| `SQLSTATE 42P01`(監査テーブルがありません) | そのサービスがまだ `AuditSchema.migrate` を実行していない(P05 以降でサービスが作る) |
| `SQLSTATE 42501`(権限がありません) | `AUDIT_DB_USER` が監査テーブルを読めるロールか |
| `S3 ... AccessDenied` | 検査は読み取り専用の `eiaf-audit-verify`(`.env` の `AUDIT_VERIFY_S3_ACCESS_KEY` / `AUDIT_VERIFY_S3_SECRET_KEY`)で行う。`make env` の後に `make up` で SeaweedFS に反映されているか、`seaweedfs-init` の結果(`make logs SERVICE=seaweedfs-init`)を確かめる |
| `tools/audit-verify をビルドできません` | `JAVA_HOME` が JDK 21 以上を指しているか |
