# Runbook: レガシーの CDC の再同期とレガシーの DDL の変更

## 対象
レガシー基幹(legacy-sim)の受注表 → Debezium(コネクタ `legacy-juchu`)→ 生の CDC → legacy-order-acl → `sales.legacy-order.changed.v1`(INT-SALES-003)。
照合(ADR-0027)がずれを見つけたときの再同期と、レガシーの表の定義(DDL)を変えるときの手順。アラートの一覧と ACL の運用は `docs/runbooks/legacy-order-acl.md`。

以下のコマンドは、ローカル基盤(`make up PROFILE=cdc`)の例。`C` は次の省略:
```bash
C="docker compose -f infra/local/docker-compose.yml --env-file infra/local/images.env --env-file infra/local/.env"
```

## ずれを見つけたとき
照合は、比べ直しても食い違うキーを「ずれ」として、種類ごとに数える(`eia_reconcile_drift_keys{kind}`、ACL のログの `照合: ずれ …`)。
1. ずれのキーを全部見る(比べるだけ。取り直さない):
   ```bash
   $C run --rm --no-deps legacy-order-acl reconcile --dry-run
   # DRIFT STALE J000000004 / DRIFT MISSING J000000007 / DRIFT EXTRA J000000099 / UNCONVERTIBLE J000000010
   ```
2. 種類ごとに、原因の当たりを付ける(直す前に、また起きないかを見る):

   | 種類 | 意味 | よくある原因 |
   |---|---|---|
   | `MISSING` | レガシーにあるが、出力にない | 変更が DLQ に入っていないか(`legacy-order-acl.md#dlq-に入ったとき`)、ACL の変換の不具合、コネクタのオフセットの消失 |
   | `STALE` | 状態が違う | 途中の変更の取りこぼし(スロットの無効化 #80 と同じ種類の事故)、出力に直接書かれた値 |
   | `EXTRA` | レガシーにないが、出力に値がある | 削除の変更の取りこぼし(tombstone が出ていない) |
   - 変換できないキー(`UNCONVERTIBLE`)はずれではない(既知の差。DLQ の対応で直す)。
3. 自動の再同期の結果を見る(既定で有効。ADR-0027 §6):
   - ACL のログの `再同期: Snapshot を指示 N 件、tombstone M 件` → 次の照合で一致に戻ることを確かめる(ダッシュボード CDC — Legacy の「ずれのキー」)。
   - `ずれ N 件が再同期の上限 100 件を超えたため、取り直しません` → **自動では何もしていない**(一部だけを取り直すこともしない)。`LegacyReconcileDriftOverLimit`(critical)。個々のキーではなく、仕組みの問題(スロットの無効化・ACL の不具合・コネクタのオフセットの消失)を疑い、原因を直してから [全体の再同期](#全体の再同期) を判断する。
   - 自動の再同期を止めているとき(`LEGACY_ORDER_ACL_RECONCILE_AUTO_RESYNC=false`)は、[部分の再同期](#部分の再同期) を手で行う。

## 部分の再同期
ずれたキーだけを取り直す。
- **照合のコマンドで行う(推奨)**: `--dry-run` を付けずに実行すると、定期の照合と同じく、上限(100 件)の範囲で取り直す。
  ```bash
  $C run --rm --no-deps legacy-order-acl reconcile
  ```
  - `MISSING`・`STALE`: signal 表に、そのキーだけの Incremental Snapshot を書く(`eiaf_resync` のロール。Debezium が今の状態を送り直し、ACL が出力に書く)。
  - `EXTRA`: レガシーにないことを確かめ直してから、出力に tombstone を書く。tombstone の `ce_source` は `/sales/legacy-order-acl/reconcile`(変換の削除 `/sales/legacy-order-acl` と見分けられる)。
- **手で signal を書く**(照合が動かせないとき。DBA が所有者か `eiaf_resync` で):
  ```sql
  INSERT INTO eiaf_cdc.debezium_signal (id, type, data) VALUES (
    'resync-<日付>-1', 'execute-snapshot',
    '{"data-collections": ["public.t_juchu"], "type": "incremental",
      "additional-conditions": [{"data-collection": "public.t_juchu", "filter": "col_02 IN (''J000000004'', ''J000000007'')"}]}'
  );
  ```
  `filter` は Debezium がそのまま SQL として使う。注文番号は SQL の文字列のリテラルにし、`'` は `''` にする。`EXTRA` は Snapshot では消えない(照合のコマンドで消す)。
- 取り直した後、`reconcile --dry-run` が終了コード 0(一致)で終わることを確かめる。

## 全体の再同期
ずれが上限を超えた、またはスロットが無効になった(`CdcSlotLost`)とき。
1. 原因を直す(コネクタ・ACL・スロット)。
2. **スロットが有効なら**: 表の全体の Incremental Snapshot を指示する(条件なし)。Debezium は表を少しずつ読み、その間の変更とも整合させる(Framework 8.2)。
   ```sql
   INSERT INTO eiaf_cdc.debezium_signal (id, type, data) VALUES (
     'resync-full-<日付>', 'execute-snapshot', '{"data-collections": ["public.t_juchu"], "type": "incremental"}');
   ```
3. **スロットが無効(lost)なら**: スロットとコネクタのオフセットを作り直し、初回の Snapshot から始める(`snapshot.mode=initial`。オフセットがないと全件を読む)。
   ```bash
   curl -X PUT localhost:19083/connectors/legacy-juchu/stop
   curl -X DELETE localhost:19083/connectors/legacy-juchu/offsets
   $C exec -T postgres psql -U postgres -c "SELECT pg_drop_replication_slot('legacy_juchu')"
   curl -X PUT localhost:19083/connectors/legacy-juchu/resume   # スロットを作り直し、初回の Snapshot を行う
   ```
4. Snapshot は削除を作れないので、最後に照合で `EXTRA` を消す。`EXTRA` が上限を超えるときは、その実行だけ上限を上げる:
   ```bash
   $C run --rm --no-deps -e LEGACY_ORDER_ACL_RECONCILE_RESYNC_LIMIT=100000 legacy-order-acl reconcile
   ```
5. `reconcile --dry-run` が一致で終わり、`LegacyReconcileDrift*` が解消することを確かめる。
6. 受信側への連絡: 送り直しで、同じ状態が重複して届く(At-Least-Once。消費者は Upsert で冪等)。照合の tombstone は `ce_source` で見分けられる。

## レガシーの DDL を変えるとき
生の CDC のスキーマは、Converter が Apicurio のグループ `cdc-raw` に自動で登録し、互換性のルール FULL_TRANSITIVE で検査する(ADR-0026 §4)。互換でない DDL を本番に入れると、登録が拒否されてコネクタのタスクが FAILED になり、**取り込みが止まる**。

### 1. 変更を分類する
| 変更 | 互換性 | 例 |
|---|---|---|
| default のある NULL 可の列の追加・削除 | 互換 | `ALTER TABLE t_juchu ADD COLUMN col_09 VARCHAR(20)` |
| NOT NULL の付け外し、型の変更(桁の拡張を含む)、列名の変更、NOT NULL の列の追加・削除 | **互換でない** | `ALTER TABLE t_juchu ALTER COLUMN col_05 DROP NOT NULL` |
迷ったら、次の 2 の事前の確認で決める(推測で本番に入れない)。

### 2. 事前に互換性を確かめる(本番で試さない)
1. ローカル基盤(`make up PROFILE=cdc`)の legacy-sim に同じ DDL を当て、受注を 1 件書く(`make legacy-simulate ARGS="seed 1"`)。コネクタが新しい版のスキーマを作る(ローカルは互換でなくても、まず試す)。
2. ローカルで作られたスキーマを取り出し、本番のレジストリに **`dryRun=true`** で試す(登録はされない。互換なら 200、互換でなければ 400 と理由):
   ```bash
   curl -fsS "http://localhost:19081/apis/registry/v3/groups/cdc-raw/artifacts/_cdc.legacy.public.t_juchu-value/versions/branch=latest/content" > new.avsc
   python3 -c 'import json; print(json.dumps({"content": {"content": open("new.avsc").read(), "contentType": "application/json"}}))' > body.json
   curl -s -w '\n%{http_code}\n' -X POST "$PROD_REGISTRY/groups/cdc-raw/artifacts/_cdc.legacy.public.t_juchu-value/versions?dryRun=true" \
     -H 'Content-Type: application/json' --data @body.json
   ```
   キーのスキーマ(`…-key`)も同じように試す(キーの列 `col_02` を変えるとき)。

### 3. 互換なら
1. ACL がその列を使うなら、先に ACL を変えてデプロイする(使わない列なら不要。ACL は使わない項目を読み飛ばす)。
2. 本番に DDL を当てる。
3. コネクタとタスクが RUNNING のままで、レジストリに新しい版があることを確かめる。照合が一致のままであることを確かめる。

### 4. 互換でないなら(段取り)
生の CDC を読むのは ACL だけ(ADR-0026 §3)なので、公開の契約には影響しない。次の順で進める:
1. **ACL を先に変える**: 古い形と新しい形の両方を読めるようにしてデプロイする(例: `col_05` が NULL 可になるなら、Envelope の型を `String?` にし、変換の規則で NULL を `MISSING_VALUE` にする)。
2. **その生のアーティファクトだけ、互換性のルールを一時的に外す**(グローバルの FULL_TRANSITIVE は残す):
   ```bash
   R=http://localhost:19081/apis/registry/v3/groups/cdc-raw/artifacts
   for a in _cdc.legacy.public.t_juchu-value _cdc.legacy.public.t_juchu-key; do
     curl -fsS -X POST "$R/$a/rules" -H 'Content-Type: application/json' -d '{"ruleType":"COMPATIBILITY","config":"NONE"}'
   done
   ```
3. DDL を当てる。タスクが FAILED なら再起動する(`curl -X POST 'localhost:19083/connectors/legacy-juchu/restart?includeTasks=true&onlyFailed=true'`)。
4. 新しい形の変更が出力まで届くことを確かめる。
5. **ルールを戻す**(以後の変更はまた検査される):
   ```bash
   for a in _cdc.legacy.public.t_juchu-value _cdc.legacy.public.t_juchu-key; do
     curl -fsS -X DELETE "$R/$a/rules/COMPATIBILITY"
   done
   ```
6. [全体の再同期](#全体の再同期) を行い、照合で一致を確かめる(形の変わり目の取りこぼしを残さない)。

### 5. 取り込みが止まったとき(互換でない DDL を先に当ててしまった)
- 症状: コネクタのタスクが FAILED(`CdcConnectorDown`)。Connect のログに `Incompatible artifact`。スロットは WAL を保持し続ける(`CdcSlotLagHigh` → `CdcSlotWalAtRisk`。上限は `max_slot_wal_keep_size`)。
- 回復: 4 の 1〜5 を行う(ACL を先に変え、ルールを外し、タスクを再起動し、ルールを戻す)。スロットが有効な間に再開すれば、止まっている間の変更は欠けない。
- 回復が間に合わずスロットが無効(lost)になったら、[全体の再同期](#全体の再同期) の 3 から行う。
- この流れ(互換でない DDL → FAILED → ルールを外して再起動 → 再開)は、`LegacyCdcIT` で確かめている。
