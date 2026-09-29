# Runbook: 監査記録の改竄の検査(make audit-verify)

## 対象
- 監査記録(`audit.audit_log`)とアンカー(S3 の `eiaf-audit` の `anchors/{service}/{date}.json`)を定期的に検査するとき。
- `make audit-verify SERVICE=<name>` が終了コード 1(改竄の疑い)か 2(実行できない)を返したとき。

仕組みは ADR-0017 を参照。

## 実行
```bash
export JAVA_HOME=<JDK 21 以上>   # Apple Silicon では aarch64 の JDK
make up PROFILE=file              # PostgreSQL と SeaweedFS
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
| `unknown_canonical_version` | 直列化の方法がない版 | `canonical_version` の書き換えか、新しい版に対応する前の `tools/audit-verify` を使っていないか |
| `anchor_hash_mismatch` / `anchor_record_missing` | アンカーより前の記録の書き換え、またはアンカーより後の記録の削除 | アンカーの `seq` と、現在の末尾の `seq` |
| `anchor_delete_marker` | アンカーを消そうとした跡(削除マーカー) | 削除マーカーを作った資格情報。audit の資格情報はバケットポリシーで拒否されるため、管理者の資格情報の利用を調べる |
| `anchor_not_compliance` / `anchor_retention_too_short` | アンカーの保持の設定が弱い | 保存したアプリの設定(保持期間)。バケットの既定の保持設定が書き換えられていないか |
| `anchor_invalid` | アンカーの形式・キーの不一致・読めない | その版の内容。audit の資格情報で読めるか(バケットポリシー) |

5. 本番では、セキュリティのインシデントとして扱い、所定の連絡先に報告する。ローカルでは、統合テストや手作業による改竄の再現でないかを確かめる。

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
