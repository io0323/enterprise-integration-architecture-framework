# Runbook: ファイル授受(file-exchange)

## 対象
- ファイル授受のサービス `file-exchange`(ADR-0032)。日次の売上ファイル(INT-SALES-004)を受け取り、取引先に送るための受け渡しの場所に写す。
  - 受け取り: バケット `eiaf-file-exchange` の受け口 `inbox/sales-daily/` に `.manifest.json` が届くと、DAG `file-exchange.receive`
    (パーティションはファイルの名前)が動き、検査して `archive/sales-daily/`(合格)か `quarantine/sales-daily/`(不合格)に移す。
  - 受け渡し: 同じ DAG のステップ `handoff` が、合格のファイルのデータファイルとマニフェスト(最後)を、同じバケットの `outbound/sales-daily/` に写す。
  - 見回り: 5 分ごとに受け口を見て、受け取り済みの名前で届いたファイルを `quarantine/sales-daily/extra/{番号}/` に移す。
- **取引先への送信は b2b-gateway**(P12 ⑦b。ADR-0040 §7)。受け渡しの場所から、台帳でデータセットを受け取る取引先に PGP で送る。
  送れないとき・ホスト鍵・PGP の鍵・取引先を足す手順は `docs/runbooks/b2b-partner-credentials.md`(「送るファイル」ほか)。
  file-exchange は取引先の台帳・鍵を読まない。過去の配送の記録 `exchange.delivery` は読み取り専用で残している(ADR-0032 §8)。
- 送り手は batch-etl(`docs/runbooks/batch-rerun.md#売上ファイルを置き直すとき`)。
- ローカルの起動: `make up PROFILE=file`(受け取りと受け渡しだけ)。取引先に送るところまでは `make up PROFILE="file b2b"`、
  batch-etl から一通り動かすときは `make up PROFILE="order batch file b2b"`。

| アラート | 重大度 | 見る節 |
|---|---|---|
| `FileExchangeQuarantined` | warning | [quarantine に移したとき](#quarantine-に移したとき) |
| `FileExchangeNameReused` | warning | [名前の使い回し](#名前の使い回し) |
| `FileExchangeInboxStale` | warning | [受け口に滞留しているとき](#受け口に滞留しているとき) |
| `BatchRunFailed`(`batch_dag="file-exchange.receive"`) | warning | [受け取り・受け渡しが失敗したとき](#受け取り受け渡しが失敗したとき) |
| `B2bFileDeliveryFailing` | warning | `docs/runbooks/b2b-partner-credentials.md` の「送るファイル」 |

## まず見るもの
- ダッシュボード **File — Sales daily**(Grafana のフォルダ EIAF): 受け取った数・quarantine の理由・受け口の滞留・受け渡しの数。
  取引先への送信は **B2B — EDI gateway** のファイルの送信のパネル。
- ログ(`make logs SERVICE=file-exchange`)。ファイルの中身は出ない。
- 受け取りの記録(`file_exchange` の DB。スキーマ `exchange`):
  ```sql
  SELECT file_name, destination, reason, state, received_at, stored_at FROM exchange.received_file ORDER BY received_at DESC LIMIT 20;
  SELECT id, file_name, reason, state, detected_at FROM exchange.extra_arrival ORDER BY id DESC LIMIT 20;
  ```
- 取引先に送った記録は b2b-gateway の DB(`b2b_gateway` のスキーマ `b2b`): `SELECT name, partner_id, remote_name, state, sent_at FROM b2b.file_delivery ORDER BY name DESC LIMIT 20;`
- 実行の記録(同じ DB のスキーマ `batch`): `SELECT partition_key, status, finished_at FROM batch.batch_run WHERE dag_id = 'file-exchange.receive' ORDER BY started_at DESC LIMIT 20;`
- トレース(Tempo)は `file-exchange.receive run` の span から探す。親は送り手(batch-etl)の書き出しの span(マニフェストの traceparent)。

## コマンド
`make up PROFILE=file` で起動した基盤で、同じイメージ・同じ環境のコンテナを 1 回だけ動かす(アプリのロールだけ)。

| コマンド | 内容 |
|---|---|
| `make file-exchange ARGS="receive <名前>"` | そのファイルの受け取りと受け渡しを実行する。失敗・中断した実行は、失敗したステップから再開する(受け取り済みなら受け渡しだけ)。成功した実行は何もしない |
| `make file-exchange ARGS="receive <名前> --restart"` | 最初から実行し直す。受け取りの記録が `STORED` なら移動はせず、受け渡しの場所に同じ中身を写し直すだけ(b2b-gateway は送信の記録のある取引先には送らない) |
| `make file-exchange ARGS="sweep"` | 受け口を 1 回だけ見回る |
| `make file-exchange ARGS="handoff-unsent [--days N] [--execute]"` | 切り替えの 1 回きりの移行。[取引先への送信の切り替え](#取引先への送信の切り替え) |

`<名前>` はファイルの名前の拡張子を除いた部分(例 `sales_daily_20261013150000_001`)。

## quarantine に移したとき
`FileExchangeQuarantined`。受け取ったファイルが検査で不合格になった。取り込みも取引先への送信もしていない。

1. `quarantine/sales-daily/{名前}.reason.json` と `exchange.received_file` の `reason` で理由を確かめる。

   | 理由 | 意味 | 対応 |
   |---|---|---|
   | `data_missing` | マニフェストはあるがデータファイルがない(欠損) | 送り手に置き直してもらう |
   | `checksum_mismatch` / `size_mismatch` | 中身がマニフェストと違う(改竄・転送の誤り) | 送り手に置き直してもらう。続くなら経路を調べる |
   | `record_count_mismatch` / `content_unreadable` / `schema_mismatch` | 件数・形式・列が契約と違う | 送り手の不具合。契約(`contracts/files/sales_daily.v1.yaml`)と照らす |
   | `manifest_invalid` / `name_mismatch` / `schema_version_unknown` / `format_unsupported` | マニフェストの誤り・知らない版 | 送り手の不具合。新しい版なら、先に受け手が契約に対応する |
2. 送り手は **新しい版**(名前の連番を変える)で置き直す。batch-etl なら `make batch-etl ARGS="sales-file <日付> --restart"`。
   同じ名前で置き直すと [名前の使い回し](#名前の使い回し) になり、取り込まない。
3. quarantine のファイルは調べ終えるまで消さない(保存期間はライフサイクル。#22)。

## 名前の使い回し
`FileExchangeNameReused`。受け取り済みの名前で、中身(SHA-256)の違うファイルが届いた。見回りが `quarantine/sales-daily/extra/{番号}/` に移した。
同じ中身の送り直し(`duplicate`)はアラートにしない(同じ場所に移すだけ)。

- 受け取り済みのファイル(archive)はそのまま。取引先にも送らない。
- 送り手に、新しい版(連番を変える)で置き直してもらう。中身が直っているかは送り手と確かめる。

## 受け口に滞留しているとき
`FileExchangeInboxStale`。受け口に 1 時間を超えて残っているファイルがある。

1. `make ps` で file-exchange が healthy か、ログに受け口を見回れない警告(`受け口を見回れません`)が出ていないか確かめる。
2. データファイルだけがありマニフェストがない(送り手が書いている途中で止まった)なら、受け取りは起動しない。送り手に置き直してもらう。
3. マニフェストがあるのに受け取りが動いていなければ、`batch.batch_run` でその名前の実行の状態を見て、`make file-exchange ARGS="receive <名前>"`。

## 受け取り・受け渡しが失敗したとき
`BatchRunFailed`(`batch_dag="file-exchange.receive"`)。受け取り(`receive`)か受け渡し(`handoff`)のステップが失敗した。

1. ログと `batch.batch_run` で、どのファイルのどのステップかを確かめる。多くは S3 互換ストレージ(SeaweedFS)の不調(`file_store_unavailable`)。
2. 原因を除いてから `make file-exchange ARGS="receive <名前>"`。失敗したステップから再開する(受け取り済みなら受け渡しだけ)。
3. 受け渡しは写し直しなので冪等。b2b-gateway は、受け渡しの場所のファイルと取引先の組ごとに送信の記録がないものだけを送る。

## 取引先への送信の切り替え
P12 ⑦b(ADR-0032 §8・ADR-0040 §7)で、取引先への送信を file-exchange から b2b-gateway に移した。切り替えの時点で、archive に移したが
過去の配送の記録(`exchange.delivery`)で `SENT` になっていないファイル(送信中で止まった・まだ送っていない)は、そのままでは誰も送らない。
切り替えのときに **1 回だけ**、そうしたファイルを受け渡しの場所に写す。

1. 切り替えの前のバージョンの file-exchange を止める(`make down` ではなく、file-exchange のコンテナだけ。受け取りの途中なら終わるのを待つ)。
2. 新しいバージョンで起動する: `make up PROFILE="file b2b"`(`file-exchange-migrate` が V3 で `delivery` を読み取り専用にする。b2b-gateway と受け渡しの identity `eiaf-b2b-handoff` も起動する)。
3. 対象を一覧する(既定は一覧だけで、何も写さない):
   ```bash
   make file-exchange ARGS="handoff-unsent"
   ```
   - 対象は、受け取りの記録が `STORED`(合格)で、直近 14 日(`--days N` で 1〜366 日)に受け取ったファイルのうち、配送の記録がない・`SENT` でない記録があるもの。
   - 14 日は受け渡しの場所の保存期間(`B2B_GATEWAY_OUTBOUND_RETENTION`)と同じ。それより古いファイルを送るかは取引先と決め、送るなら `--days` を広げる。
4. 一覧を確かめてから写す:
   ```bash
   make file-exchange ARGS="handoff-unsent --execute"
   ```
   - 受け取りの実行と同じロックで写すので、受け取りと並んで動いても壊れない。写せなかったファイルがあると終了コードが失敗になる。原因を除いてもう一度実行する(写し直しは冪等)。
5. b2b-gateway が送ったことを確かめる(`B2B_GATEWAY_DELIVER_INTERVAL`。既定 1 分):
   ```sql
   -- b2b_gateway の DB
   SELECT name, partner_id, state, already_present, sent_at FROM b2b.file_delivery ORDER BY name DESC LIMIT 20;
   ```
   - 前のバージョンが取引先の側に送り終えていた(記録する前に止まった)ファイルは、送る前の正式名の確認で送り直さず `already_present` になる。
6. 送れないときは `docs/runbooks/b2b-partner-credentials.md` の「送るファイル」。

## ホスト鍵・PGP の鍵・取引先を足す
取引先への送信は b2b-gateway に移したので、手順は `docs/runbooks/b2b-partner-credentials.md`(「取引先を足す」「取引先の PGP の鍵を差し替える」「ホスト鍵と自社の PGP の鍵」)。
