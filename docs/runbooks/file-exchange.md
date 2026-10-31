# Runbook: ファイル授受(file-exchange)

## 対象
- ファイル授受のサービス `file-exchange`(ADR-0032)。日次の売上ファイル(INT-SALES-004)を受け取り、取引先に送る。
  - 受け取り: バケット `eiaf-file-exchange` の受け口 `inbox/sales-daily/` に `.manifest.json` が届くと、DAG `file-exchange.receive`
    (パーティションはファイルの名前)が動き、検査して `archive/sales-daily/`(合格)か `quarantine/sales-daily/`(不合格)に移す。
  - 配送: 同じ DAG のステップ `deliver` が、合格のファイルを取引先の台帳(`infra/local/b2b/partners.json`。b2b-gateway と共有する 1 つの台帳。ADR-0040)の取引先ごとに、
    PGP で暗号化・署名して SFTP で送る。ローカルの取引先は `sftp-b2b` の `partner01`(`/config/outbox`)。
  - 見回り: 5 分ごとに受け口を見て、受け取り済みの名前で届いたファイルを `quarantine/sales-daily/extra/{番号}/` に移す。
- 送り手は batch-etl(`docs/runbooks/batch-rerun.md#売上ファイルを置き直すとき`)。
- ローカルの起動: `make up PROFILE=file`(受け取りと配送だけ)。batch-etl から一通り動かすときは `make up PROFILE="order batch file"`。

| アラート | 重大度 | 見る節 |
|---|---|---|
| `FileExchangeQuarantined` | warning | [quarantine に移したとき](#quarantine-に移したとき) |
| `FileExchangeNameReused` | warning | [名前の使い回し](#名前の使い回し) |
| `FileExchangeInboxStale` | warning | [受け口に滞留しているとき](#受け口に滞留しているとき) |
| `FileExchangeDeliveryFailed` / `FileExchangePartnerCircuitOpen` | warning | [取引先に送れないとき](#取引先に送れないとき) |
| `BatchRunFailed`(`batch_dag="file-exchange.receive"`) | warning | [取引先に送れないとき](#取引先に送れないとき)(受け取りの失敗も同じ手順) |

## まず見るもの
- ダッシュボード **File — Sales daily**(Grafana のフォルダ EIAF): 受け取った数・quarantine の理由・受け口の滞留・配送の結果・取引先の Circuit Breaker。
- ログ(`make logs SERVICE=file-exchange`)。配送の失敗は `<名前> を取引先 <ID> に送れません(<code>)` が WARN で出る。ファイルの中身は出ない。
- 受け取りと配送の記録(`file_exchange` の DB。スキーマ `exchange`):
  ```sql
  SELECT file_name, destination, reason, state, received_at, stored_at FROM exchange.received_file ORDER BY received_at DESC LIMIT 20;
  SELECT file_name, partner_id, remote_name, state, already_present, started_at, sent_at FROM exchange.delivery ORDER BY started_at DESC LIMIT 20;
  SELECT id, file_name, reason, state, detected_at FROM exchange.extra_arrival ORDER BY id DESC LIMIT 20;
  ```
- 実行の記録(同じ DB のスキーマ `batch`): `SELECT partition_key, status, finished_at FROM batch.batch_run WHERE dag_id = 'file-exchange.receive' ORDER BY started_at DESC LIMIT 20;`
- トレース(Tempo)は `file-exchange.receive run` の span から探す。親は送り手(batch-etl)の書き出しの span(マニフェストの traceparent)。

## コマンド
`make up PROFILE=file` で起動した基盤で、同じイメージ・同じ環境のコンテナを 1 回だけ動かす(アプリのロールだけ)。

| コマンド | 内容 |
|---|---|
| `make file-exchange ARGS="receive <名前>"` | そのファイルの受け取りと配送を実行する。失敗・中断した実行は、失敗したステップから再開する(受け取り済みなら配送だけ)。成功した実行は何もしない |
| `make file-exchange ARGS="receive <名前> --restart"` | 最初から実行し直す。受け取りの記録が `STORED` なら移動はせず、送信済みの取引先にも送らない(どちらも記録で冪等) |
| `make file-exchange ARGS="sweep"` | 受け口を 1 回だけ見回る |

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

## 取引先に送れないとき
`FileExchangeDeliveryFailed`・`FileExchangePartnerCircuitOpen`。取引先の SFTP に送れず、配送の記録が `SENDING` のまま残っている。
ほかの取引先への配送は止まらない(取引先ごとに Circuit Breaker を持つ。Resilience の名前 `sftp-{取引先の ID}`)。

1. ログの失敗のコードで原因を分ける。

   | コード・理由 | 原因 | 対応 |
   |---|---|---|
   | `file_remote_unavailable` / `circuit_open` | 取引先の SFTP に接続できない・止まっている | 取引先の復旧を待つ。戻れば再実行で送る |
   | `file_remote_rejected`(`HostKeyMismatch`) | 取引先のホスト鍵が登録と違う | **なりすましの可能性**。送らずに止め、[ホスト鍵](#ホスト鍵) の手順で取引先と確かめる |
   | `file_remote_rejected`(`AuthenticationFailed` / `PermissionDenied` / `NoSuchPath`) | 鍵・利用者・送り先のディレクトリの誤り | 取引先と設定を確かめ、台帳か鍵を直す |
   | `file_remote_rejected`(`NoCommonAlgorithm`) | 取引先が古い暗号の方式しか持たない | 方式を足さずに、取引先に更新を頼む(ADR-0032 §8) |
   | `file_credential_rejected` / `file_credential_unavailable` | 鍵・known_hosts・PGP の鍵を読めない・形式の誤り | 秘密情報のファイルを確かめる([PGP の鍵](#pgp-の鍵)) |
2. 原因を除いてから `make file-exchange ARGS="receive <名前>"`。送れなかった取引先だけに送る。
3. 送り直しは冪等: 送る前に取引先の側に同じ正式名(`{データファイル}.pgp`)があるかを確かめ、あれば送らずに送信済みにする
   (`exchange.delivery` の `already_present = true`)。前の実行が正式名に変えた後、記録する前に落ちた場合。
   取引先が受け取ってすぐに消していると送り直すことがあるので、取引先の側でファイル名による重複の検知が要る(取り決めに書く)。

## ホスト鍵
取引先の SFTP のホスト鍵は、台帳の `knownHostsSecret` の名前の秘密情報(known_hosts の形)に登録したものだけを受け付ける。
初めての接続で鍵を覚える方式(TOFU)と、検証を外す設定はない(ADR-0032 §8)。

- **本番の登録**: 取引先から接続先(ホスト・ポート)を受け取り、
  1. `ssh-keyscan -p <ポート> -t ed25519,ecdsa,rsa <ホスト>` で鍵を取る。
  2. `ssh-keygen -lf <取った行のファイル>` のフィンガープリントを、**取引先と別の経路**(電話・署名つきの書面など)で照合する。
     照合できるまで登録しない(取った経路が乗っ取られていると、偽の鍵を登録してしまう)。
  3. 照合した行を秘密情報のファイルに置く。file-exchange は送受信のたびに読み直すので、再起動は要らない。
- **開発用(sftp-b2b)**: `make env` が `infra/local/secrets/sftp-b2b-host-key` を作り、sftp-b2b は起動時にこの鍵をホスト鍵にする
  (`infra/local/sftp/20-host-key.sh`)。known_hosts(`secrets/sftp-b2b.known_hosts`)は同じ鍵から作るので、照合は要らない。
  作り直すときは `secrets/sftp-b2b-host-key*` と `secrets/sftp-b2b.known_hosts` を消して `make env` と `make up PROFILE=file`。
  `make verify PROFILE=file` が、登録した鍵で接続できること・別の鍵を登録した known_hosts では接続しないことを確かめる。
- **鍵が登録と違う**(`HostKeyMismatch`): 取引先に、ホスト鍵を変えたかを別の経路で確かめる。変えていれば上の手順で登録し直す。
  変えていなければ、経路の乗っ取りを疑って送信を止めたまま調べる。

## PGP の鍵
- 送るファイルは、取引先の公開鍵(台帳の `pgp.publicKeySecret`)で暗号化し、自社の秘密鍵(`FILE_EXCHANGE_PGP_SECRET_KEY`・`_PASSPHRASE`)で署名する。
  鍵は送るたびに読み直すので、秘密情報のファイルを差し替えれば次の送信から使う(再起動は要らない)。
- 取引先の公開鍵を受け取ったら、主鍵のフィンガープリントを取引先と別の経路で照合してから置く(`gpg --show-keys <ファイル>`)。
- **開発用**: `make pgp-keys`(`make up PROFILE=file` も実行する)が、GnuPG(`infra/local/images/gnupg`)で
  `secrets/pgp-eiaf.*`(自社)と `secrets/pgp-partner01.*`(取引先の代わり)を作る。RSA 3072、主鍵は署名、副鍵は暗号化、パスフレーズで保護。
  作り直すときは `secrets/pgp-*` を消して `make pgp-keys`。
  `make verify PROFILE=file` が、取引先の側として届いたファイルを GnuPG で復号し、署名と改竄の検知を確かめる(Bouncy Castle と GnuPG の相互運用)。

## 取引先を足す
1. 台帳(`infra/local/b2b/partners.json`)に取引先を足す。項目(識別子・扱う文書・送り先・鍵とその期日・合意・連絡先)は `docs/runbooks/b2b-partner-credentials.md` の「取引先を足す」を参照。送るデータセットは `documents` に `{"integration": "INT-SALES-004", "direction": "outbound", "dataset": "sales_daily"}` で書く。
2. 秘密情報(ホスト鍵・クライアントの鍵・PGP の公開鍵)を、上の照合をしてから置く。compose では `<名前>_FILE` で渡す。
3. file-exchange を再起動する(台帳は起動時に読む。Resilience `sftp-{ID}` とメトリクスも取引先ごとに作られる)。
