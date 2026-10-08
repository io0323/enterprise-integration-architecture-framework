# Runbook: レガシーの受注の CDC の Anti-Corruption Layer(legacy-order-acl)

## 対象
レガシー基幹(legacy-sim)の受注表 → Debezium(コネクタ `legacy-juchu`)→ 生の CDC `_cdc.legacy.public.t_juchu` → **legacy-order-acl** → `sales.legacy-order.changed.v1`(INT-SALES-003)の、ACL の段。設計は ADR-0026。

| アラート | 重大度 | 見る節 |
|---|---|---|
| `LegacyAclDeadLetters` | warning | [DLQ に入ったとき](#dlq-に入ったとき) |
| `LegacyAclLagHigh` | warning | [処理が遅れているとき](#処理が遅れているとき) |
| `LegacyAclDown` | critical | [ACL が動いていないとき](#acl-が動いていないとき) |

レガシーのスロット `legacy_juchu` とコネクタ `legacy-juchu` のアラート(`Cdc*`。`slot_name` / `connector` のラベルで見分ける)は `docs/runbooks/cdc-outbox-lag.md`。

## まず見るもの
- Grafana の **CDC — Legacy**(http://localhost:19300/d/eiaf-cdc-legacy): lag、結果ごとの件数、DLQ の原因ごとの件数、読み直し。
- ACL のログ(`make logs SERVICE=legacy-order-acl`)。DLQ に送ったときは `変換できない変更を DLQ に送りました(reason=..., partition=..., offset=...)` が WARN で出る。ログに値(顧客名・金額)は出ない。
- ready: `docker compose ... exec legacy-order-acl /probe/bin/wget -qO- http://127.0.0.1:8081/health/ready`(503 なら一時的な失敗で読み直している)。

## DLQ に入ったとき
`LegacyAclDeadLetters`。**本流は止まっていない**(後続の変更は通常どおり変換している)。DLQ に入った変更は、その時点の注文の状態が出力に出ていない。

1. 原因を確かめる。DLQ のレコードのヘッダ(値は見ない)。
   ```bash
   docker compose -f infra/local/docker-compose.yml --env-file infra/local/images.env --env-file infra/local/.env \
     exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 \
     --topic _cdc.legacy.public.t_juchu.dlq --from-beginning --timeout-ms 5000 --property print.key=true --property print.headers=true \
     | grep -a -o -E '(eiaf\.dlq\.[a-z.-]+:[^,]*)' | sort | uniq -c
   ```
   - キーは生の CDC のキーのまま(Converter の Avro。注文番号の文字列を含む)、`eiaf.dlq.reason` は原因の種類、`eiaf.dlq.detail` は列と破った規則(値は入らない)、`eiaf.dlq.source.*` は生の CDC の位置。
   - DLQ のトピックは **confidential**(受け取ったバイト列のままで、顧客名などを含む)。中身を読むのは調査に必要な人だけにし、外に持ち出さない。保持は 7 日。
2. 原因の種類ごとの対応(ADR-0026 §8)

   | reason | 意味 | 対応 |
   |---|---|---|
   | `UNKNOWN_STATUS_CODE` | 状態区分が対応表にない | レガシーで新しいコードが使われ始めたか、データの誤り。新しいコードなら、対応表(domain の `LegacyOrderStatus`)と契約の enum を変える(enum の値の追加は新しい版のトピック。ADR-0014)。ADR-0026 §8 を改訂する |
   | `AMOUNT_HAS_FRACTION` | 円の金額に小数部がある | レガシーのデータの誤り(丸めない)。レガシーの担当にデータの訂正を依頼する |
   | `AMOUNT_OUT_OF_RANGE` | 負、または上限(9,999,999,999 円)を超える | 同上。上限そのものを変えるなら契約と ADR を改訂する |
   | `MALFORMED_TEXT` | 文字化け(U+FFFD・制御文字) | レガシーの入力の経路(文字コードの変換)の誤り。データの訂正を依頼する |
   | `MISSING_VALUE` | 必須の文字列が空白だけ | データの訂正を依頼する |
   | `INVALID_LOCAL_TIME` | JST に存在しない現地時刻 | JST では起きない。起きたらタイムゾーンの前提(ADR-0026 §8)を確かめる |
   | `UNDECODABLE` | Avro として読めない・形が違う | 生の CDC のスキーマ(Apicurio のグループ cdc-raw)とコネクタの設定(`dereference-schema`・REPLICA IDENTITY FULL)を確かめる |
   | `EVENT_ENCODING_FAILED` など(変換の後) | 契約と実装の食い違い・送信の拒否 | ACL の不具合。修正してデプロイする |
3. 原因を直した後の回復: **DLQ のレコードを本流に戻さない**(後から届いた同じ注文の変更より古い状態で上書きしうる。ADR-0026 §7)。代わりに、対象の注文の今の状態を、signal 表の Incremental Snapshot で送り直す(部分の再同期)。DBA(所有者のロール)が実行する。
   ```sql
   -- legacy_sim の DB に、所有者(legacy_sim)で接続して実行する。id は一意な文字列
   INSERT INTO eiaf_cdc.debezium_signal (id, type, data) VALUES (
     'resync-20261008-1', 'execute-snapshot',
     '{"data-collections": ["public.t_juchu"], "type": "incremental",
       "additional-conditions": [{"data-collection": "public.t_juchu", "filter": "col_02 IN (''J000000004'')"}]}'
   );
   ```
   送り直した変更は、ACL が通常どおり変換して出力に出す(まだ変換できなければ、また DLQ に入る)。手順の全体(件数・ハッシュの照合、範囲の決め方)は `docs/runbooks/cdc-resync.md`(P06 ⑥)。
4. アラートは、DLQ のオフセットが 10 分増えなければ解消する。

## 処理が遅れているとき
`LegacyAclLagHigh`(lag が 1,000 件を超えた状態が 10 分続く)。

1. ACL が動いているか(`LegacyAclDown` が出ていないか、ready か)を見る。ready が 503 なら、Kafka か Apicurio の一時的な失敗で読み直している(ダッシュボードの「読み直し」、ログの `一時的な失敗のため`)。依存先を復旧させる。
2. 動いていて遅いだけなら、レガシーの更新が急に増えた(一括の更新・Incremental Snapshot の実行中)。Snapshot の実行中なら終わるのを待つ。
3. ACL はパーティションの数(3)まで増やせる。ただし、同じ Consumer Group に入れ、1 つのパーティションを 1 つの ACL が順に処理することを崩さない(ADR-0026 §5)。

## ACL が動いていないとき
`LegacyAclDown`(レガシーのコネクタはあるのに、ACL のメトリクスが 2 分ない)。整形済みのトピックが更新されない。生の CDC は Kafka に残るので(保持 7 日)、**7 日以内に再開すれば欠けない**(最後にコミットした位置から読み直す。重複はありうる)。

1. `make ps` で `legacy-order-acl` の状態、`make logs SERVICE=legacy-order-acl` で原因を見る。設定の誤りは終了コード 2 で止まる。
2. 起動の順序: `kafka-topics`(トピックの作成)と `schema-publish`(契約のスキーマの登録)が終わってから起動する。どちらかが失敗していれば、その原因を直して `make up PROFILE=cdc`。
3. 再開した後、lag が減っていくことをダッシュボードで確かめる。

## 本番では
- 通知は Alertmanager か監視基盤につなぐ。`LegacyAclDown` は当番に通知する。
- DLQ のトピックは、読み取りの権限を調査の担当に限る(ローカルは未認証。secure profile は #26)。
- lag の閾値は、レガシーの更新の量と INT-SALES-003 の SLO(p99 60 秒)から決め直す。
