# ADR-0026: レガシーの CDC と Anti-Corruption Layer
- Status: Accepted
- Date: 2026-10-08
- Framework 参照章: 6.2, 6.3, 6.4, 6.5, 8.1, 8.2, 8.3, 8.4, 13, 14, 16

## Context
P06 ⑤ では、改修できないレガシー基幹の DB の変更を Log-based CDC で取り込み、Anti-Corruption Layer(ACL)で変換して、整形済みのトピックに出す(Framework 8.3 の図の下段、ROADMAP P06)。次の点を決める必要がある。
- レガシーの模擬(legacy-sim)と ACL を、どのモジュール・サービスに置くか。レガシーの側で何を変えてよいか。
- 生の CDC のトピックの形式とスキーマの登録。ADR-0025 §2 は「サービスはスキーマを自動登録しない」と決めているが、生の CDC のスキーマは DB の定義から Debezium が作る。
- レガシーの主キー(採番の連番)と、出力のキー(業務の注文番号)が違うときの、同じ注文の変更の順序。
- ACL の「読む → 変換する → 書く」の配信の保証(At-Least-Once か Exactly-Once か)。
- レガシーの値が規則に合わない(未知のコード値・範囲外の金額・文字化けなど)ときの扱い(Framework 6.5)。
- レガシーらしい表現(タイムゾーンのない現地時刻・固定長の文字列・コード値・通貨のない小数の金額)の変換の規則。

## Decision
### 1. 構成と責務
```
legacy-sim(レガシーの DB: legacy_sim。表 t_juchu)              ← レガシーのアプリは変えない
   │ pgoutput / slot legacy_juchu / publication eiaf_legacy      ← DBA の設定(§2)
   ▼
Kafka Connect: コネクタ legacy-juchu(Apicurio の AvroConverter。グループ cdc-raw)
   ▼
_cdc.legacy.public.t_juchu(生の CDC。Avro。キー = 受注番号。Envelope のまま)
   ▼ Consumer Group legacy-order-acl.translate
services/legacy-order-acl(4 モジュール。⑤b)──(変換できない)──▶ _cdc.legacy.public.t_juchu.dlq
   ▼
sales.legacy-order.changed.v1(compacted。キー = 注文番号。削除は tombstone。INT-SALES-003)
```
- **legacy-sim** は「改修できないレガシー」の模擬で、表(V1)と、レガシーのアプリの操作(`simulate`)だけを持つ。変換の知識は持たない。業務のロジックがないため、4 つのモジュールに分けず `services/legacy-sim/app` の 1 つにする(CLAUDE.md §3 の例外)。Konsist の層の規則は、ある層のファイルだけを検査するので、除外の設定は要らない。サービスのパッケージは、ディレクトリ名から `-` を除いた `io.eia.legacysim`(Konsist の `servicePackage`。`platform/messaging-kafka` → `messagingkafka` と同じ規則)。
- **ACL は別のサービス**(`services/legacy-order-acl`。domain / application / adapters / app の 4 モジュール)にする。理由:
  - ACL は連携する側(レガシーのデータを使う側)の責務で、レガシーの側には置けない(改修できない。Framework 8.4 の採用基準「ソース改修不可」)。
  - Framework 8.3 は CDC Connector と変換(ACL)を別の段に分けている。Connect の中(SMT)で業務の変換をすると、変換の規則のテストと、変換できないレコードの隔離(§7)ができない(Alternatives)。
  - 変換の規則(§8)は業務の知識なので、domain で純粋な Kotlin として持ち、commonTest で検査する。
- ACL の所有者は team-order(業務の領域は sales。カタログの `provider: { owner: team-order, system: legacy-order-acl }`)。「レガシー」はデータの出どころで、業務の領域ではない(INTEGRATION_STANDARDS §1)。

### 2. レガシーの側で変えてよい範囲(DBA の作業)
レガシーのアプリと表の定義(列・型・制約)は変えない。CDC のために DBA が行う次の設定だけを許す(legacy-sim の `V2__dba_cdc_setup.sql`)。
| 設定 | 理由 |
|---|---|
| `ALTER TABLE t_juchu REPLICA IDENTITY FULL` | 削除の変更に受注番号(キーの列)を載せる(§5)。更新の変更前の値も全部の列になる。トレードオフ: WAL の量が増える |
| `GRANT SELECT ON t_juchu TO debezium` | 初回の Snapshot(§3)。Debezium は REPLICATION と、対象の表の SELECT だけを持つ(P06 ③b と同じ方針) |
| スキーマ `eiaf_cdc` と signal 表 `eiaf_cdc.debezium_signal`。debezium に SELECT・INSERT・DELETE | Incremental Snapshot(§3)。Debezium は Snapshot の窓の印(watermark)を signal 表に書いて消す(`incremental.snapshot.watermarking.strategy=insert_delete`)。レガシーのスキーマ(public)には置かない |
| `CREATE PUBLICATION eiaf_legacy FOR TABLE t_juchu, eiaf_cdc.debezium_signal` | 公開する表を明示する(`publication.autocreate.mode=disabled`)。signal 表も変更を読むために含める |

signal 表への書き込み(再同期の指示)は DBA(所有者のロール)が行う。手順は ⑥ の Runbook(`docs/runbooks/cdc-resync.md`)に書く。

### 3. 生の CDC のトピック
- 名前は `_cdc.legacy.{schema}.{table}`(コネクタの `topic.prefix=_cdc.legacy`)。先頭の `_` は内部のトピックの印で(Connect の `_connect.*` と同じ)、業務のトピックの命名規約(`{domain}.{entity}.{event}.v{n}`)の対象外。契約(AsyncAPI)とカタログの channels には載せない(INT-SALES-003 の description に書く)。**読むのは ACL の Consumer Group だけ** とし、ほかのシステムは整形済みのトピックを読む(Framework 8.3「内部テーブルをそのままトピック公開」はアンチパターン)。
- 値は Debezium の Envelope(`before`・`after`・`op`・`source`)のままの Avro。ACL が削除(`op=d` の `before`)と変更前後の値を使えるよう、`ExtractNewRecordState` などで平らにしない。
- コネクタの主な設定(`infra/local/kafka-connect/legacy-juchu.json`):
  | 設定 | 値 | 理由 |
  |---|---|---|
  | `message.key.columns` | `public.t_juchu:col_02` | キーを受注番号にする(§5) |
  | `snapshot.mode` | `initial` | 初回は全件の Snapshot から始める(Framework 8.2・8.4 のアンチパターン「Snapshot なしの途中開始」) |
  | `signal.data.collection` | `eiaf_cdc.debezium_signal` | Incremental Snapshot(部分の再同期。⑥)を signal 表から指示する |
  | `decimal.handling.mode` | `string` | ACL の domain(純粋な Kotlin。BigDecimal がない)で、小数の文字列を正確に解析する(§8) |
  | `time.precision.mode` | `adaptive` | `TIMESTAMP(6) WITHOUT TIME ZONE` を、現地時刻をそのまま UTC のエポックとみなしたマイクロ秒(`MicroTimestamp`)で出す(§8) |
  | `tombstones.on.delete` | `false` | 生のトピックは compacted にしない(保持 7 日)。削除は `op=d` で ACL が受け取り、出力に tombstone を出す |
  | `schema.name.adjustment.mode` / `field.name.adjustment.mode` | `avro` | Avro の名前の規則に合わせる |
  | `heartbeat.action.query` | `pg_logical_emit_message(true, …)` | 変更のない間もスロットを進める(P06 ③b と同じ。メッセージは `_cdc.legacy.message` に出る) |
  | `topic.creation.default.*` | パーティション 3、`cleanup.policy=delete`、`retention.ms=604800000`(7 日) | 業務イベントの既定の保持(Framework 6.2) |
- キーの列を変えた場合(レガシーは受注番号を変えない前提だが、運用の誤りで起こりうる)、Debezium は **変更前のキーの削除(`op=d`)と、変更後のキーの登録(`op=c`)** を出す(`LegacyCdcIT` で確認)。ACL は特別な扱いをせず、削除と登録として変換すればよい(古いキーに tombstone、新しいキーに状態)。

### 4. 生の CDC のスキーマの自動登録(ADR-0025 §2 の例外)
- コネクタの AvroConverter は、スキーマを **自動で登録する**(`apicurio.registry.auto-register=true`)。ADR-0025 §2(サービスは自動登録しない。登録は `tools/schema-publish` だけ)の例外とする。
  - 理由: 生の CDC のスキーマの元は契約(contracts)ではなく、**レガシーの DB の定義**で、Debezium が表の定義から Envelope のスキーマを作る。契約として手で書くと、DB の定義と二重に持つことになり、Debezium が生成する名前・型(`MicroTimestamp` などの論理型・Envelope の構造)とずれる。
  - 自動登録するのは Converter(Kafka Connect)だけで、サービス(ACL を含む)は自動登録しない(ADR-0025 §2 のまま)。ACL が書く出力のスキーマ(`LegacyOrderChanged`)は契約で、`schema-publish` が登録する。
- 登録先のグループは **`cdc-raw`**(`apicurio.registry.artifact.group-id`。Apicurio 3.3.3 の `SchemaResolverConfig.EXPLICIT_ARTIFACT_GROUP_ID` は、アーティファクトの決め方(`TopicIdStrategy`)のグループより優先する)。契約のスキーマ(グループ `default`)と分け、契約でないスキーマが契約のグループに入らないようにする。アーティファクトは `{topic}-key` / `{topic}-value`。
- **互換性の検査は引き続き効く**: Apicurio のグローバルの互換性ルール FULL_TRANSITIVE(ADR-0014・ADR-0016 §7)は、グループ `cdc-raw` のアーティファクトにも適用される。レガシーの DDL の変更が FULL 互換でなければ(列の型の変更・NOT NULL の列の追加や削除など)、Converter の登録が拒否され、コネクタのタスクは FAILED になり、**取り込みが止まる**。これを受け入れる。
  - 止まっている間、スロットは WAL を保持する(上限は `max_slot_wal_keep_size`。監視とアラートは P06 ④)。互換性のない変更を黙って流し、ACL や消費者が壊れるより、止まって気付ける方がよい(Framework 8.4「CDC はスキーマ変更に脆い → Schema Registry 連携と DDL 変更手順で統制」)。
  - レガシーの DDL を変えるときの手順(事前の互換性の確認、互換性がないときの手順)は ⑥ の Runbook に書く。
- ACL は生のスキーマを、メッセージの contentId から取る(`WriterSchemas`。ADR-0025 §1 の受信側と同じ。新しい contentId を初めて見たときだけレジストリに問い合わせ、以後はキャッシュ)。

### 5. 順序とキー
- **問題**: 生のキーをレガシーの主キー(COL_01。採番の連番)のままにすると、出力のキー(注文番号 = COL_02)と違う。同じ注文の変更の順序が保たれるのは、主キーと注文番号が 1 対 1 で変わらない間だけになる。削除して同じ注文番号で別の連番で登録し直す(レガシーではよくある)と、同じ出力のキーの変更が、入力の別のパーティションから届き、順序が崩れうる。また、既定の REPLICA IDENTITY では削除の変更に主キーしか載らず、ACL は自分で状態(主キー → 注文番号)を持たないと tombstone を作れない。
- **決定**:
  - Debezium の `message.key.columns` で、**生のキーを注文番号にする**(生のキー = 出力のキー)。同じ注文の変更は、主キーが変わっても、生のトピックの同じパーティションに順に入る。
  - `REPLICA IDENTITY FULL`(§2)で、削除の変更にも注文番号が載る。
  - ACL は、入力のパーティションごとに、届いた順に 1 件ずつ変換して送る(並列にしない)。出力は注文番号をキーにし、Producer は冪等(`enable.idempotence=true`・`acks=all`・`max.in.flight.requests.per.connection` ≤ 5)にして、出力のパーティションの中で送った順を保つ。
  - 同じ注文の変更は、生のトピックの 1 つのパーティションから、1 つの ACL のスレッドを通り、出力の 1 つのパーティションに入る。パーティションの数が入力と出力で違っても、この関係は変わらない。
- 全体の順序(注文をまたぐ順序)は保証しない(Framework 6.2)。

### 6. 配信の保証: At-Least-Once
| | At-Least-Once(採用) | Exactly-Once(Kafka のトランザクション) |
|---|---|---|
| 手順 | 読む → 変換する → 送る → `flush`(全部の ack を待つ)→ `commitSync`(オフセット) | トランザクションの Producer で送り、`sendOffsetsToTransaction` でオフセットも同じトランザクションでコミット |
| ACL の中の重複 | 送った後・コミットの前に落ちると、最後のコミットの後の変更を、同じ順でもう一度送る | なし |
| 全体(レガシー → 消費者) | 上流の Debezium(Connect のソース)も At-Least-Once で、生のトピックにも重複が入りうる | 同じ。ACL の中だけを Exactly-Once にしても、上流の重複は残る |
| 消費者の条件 | 冪等な Upsert で足りる | `isolation.level=read_committed` が必要(既定の read_uncommitted では、中断したトランザクションのレコードも見える) |
| 運用 | 単純 | `transactional.id` の管理・トランザクションのコーディネータ・遅延の増加 |
- **決定**: At-Least-Once。Framework 6.4 は Exactly-Once を「基盤内処理に限定して適用」としており、ACL(Kafka → Kafka)はその対象になりうるが、上流が At-Least-Once のため全体では重複が残り、利点が小さい。
- **冪等の前提**: 出力は注文のヘッダの全部の項目を運ぶ State Transfer Event で、注文番号をキーにした compacted のトピックへの Upsert になる。同じ変更を送り直しても、最後の状態は同じになる。このため ACL は **状態を持たない**(DB も processed_message の表も持たない)。
- **一時的な巻き戻り**: 再送では、最後のコミットの後の変更が順に送り直される(例: A1・A2・A3 の後に A2・A3)。消費者は、A3 の後に A2 を受け取り、A3 が届くまで少し前の状態に戻ることがある。再送が終われば最新の状態に収束する。状態が戻ることを許さない消費者は、イベントの `source.lsn` で比べる(大きい方が新しい)。Snapshot のレコードは同じ LSN を共有するため、Snapshot 同士の比較には使えない(契約の AsyncAPI に書いた)。
- 処理の中で一時的な失敗(Kafka・Apicurio)が起きたら、コミットせずに最後のコミットの位置に戻り、Backoff して読み直す(`shared/resilience` の RetryPolicy)。その間 ACL の `/health/ready` は失敗にする。

### 7. 変換できないレコードと DLQ(Framework 6.5)
- 変換の規則(§8)に合わないレコードは、黙って捨てず、**DLQ `_cdc.legacy.public.t_juchu.dlq`**(入力のトピック + `.dlq`。Framework 6.5・INTEGRATION_STANDARDS §1)に送り、本流を止めない。後続の変更(同じ注文の後の変更を含む)は通常どおり変換する。
- 変換の誤りは決定的(何度やっても同じ結果)な NonRetryable の `DomainError` なので、**リトライせずに** DLQ に送る(Framework 6.5 の既定「リトライ 3 回の後」から外れる。リトライは結果を変えず、遅延だけを増やすため)。Avro として読めないレコード(`UNDECODABLE`)も同じ。一時的な失敗(Retryable)は DLQ に送らない(§6)。
- 原因の種類(`eiaf.dlq.reason`): `UNKNOWN_STATUS_CODE` / `AMOUNT_OUT_OF_RANGE` / `AMOUNT_HAS_FRACTION` / `MALFORMED_TEXT` / `INVALID_LOCAL_TIME` / `MISSING_VALUE` / `UNDECODABLE`。
- DLQ のレコード:
  - キーと値は **受け取った生のバイト列のまま**(Avro・contentId を含む)。ヘッダは元のヘッダに、次を加える。
  - `eiaf.dlq.reason`(上の種類)、`eiaf.dlq.detail`(列の名前と破った規則。**値は入れない**: ペイロードの全文のログの禁止・機密区分)、`eiaf.dlq.source.topic` / `eiaf.dlq.source.partition` / `eiaf.dlq.source.offset`、`eiaf.dlq.attempts`(リトライしないので 1)、`eiaf.dlq.failed-at`(ISO 8601 の UTC)、`traceparent`(ACL が始めたトレース)。
  - ヘッダの名前は INTEGRATION_STANDARDS に加え(⑤b)、P07 の DLQ でも同じ名前を使う。
- **DLQ のトピックの機密区分は出力と同じ confidential** とする。生のバイト列は顧客名などを含むため。保持は **7 日**(`retention.ms=604800000`。生のトピックと同じ)とし、トピックを作る設定(⑤b)に書く。
  - 7 日にする理由: 生のトピックより長く持つと、機密のデータを持つ期間が延びる。回復(下)は DLQ のレコードを使わないため、DLQ は原因の調査の間だけあればよい。DLQ に入るとアラートが出る(§10)ので、7 日の間に調査できる。
- 件数はメトリクスにする(`eia.acl.records{outcome=upserted|deleted|dead_lettered}`・`eia.acl.dead_letters{reason}`。OTel → Prometheus)。
- **回復**: DLQ のレコードを本流に戻すと、その後に届いた同じ注文の変更より古い状態で上書きし、状態が戻りうる。このため回復の手順は、**変換の規則やレガシーのデータを直した後、signal 表から対象の注文の Incremental Snapshot を指示し、今の状態を送り直す**(部分の再同期。⑥ の Runbook)。一般の Replay の CLI(P07)は、この DLQ には使わない。

### 8. 変換の規則(ACL の domain。commonTest で検査する)
| レガシー | 規則 | 合わないとき |
|---|---|---|
| `col_07` 受注日時・`col_08` 最終更新日時(`TIMESTAMP WITHOUT TIME ZONE`。JST の現地時刻) | `MicroTimestamp`(現地時刻を UTC のエポックとみなした値)→ `LocalDateTime` → `TimeZone.of("Asia/Tokyo")` で `Instant`(kotlinx-datetime。ADR-0011 §8)。マイクロ秒未満は過去方向に切り捨てる(`truncatedToMicros()`。ADR-0012 §3)。JST には夏時間がないため、存在しない・重複する現地時刻は起きないが、規則として「重複は早い方、存在しない時刻は誤り」とする | `INVALID_LOCAL_TIME` |
| `col_08` の `9999-12-31 00:00:00` | 「未設定」の番兵値。`legacyUpdatedAt = null` | — |
| `CHAR(n)`(`col_02` 受注番号・`col_04` 顧客名・`col_05` 顧客コード) | 末尾の半角の空白(U+0020)だけを除く。全角の空白(U+3000)と先頭の空白は残す | 除いた結果が空なら `MISSING_VALUE` |
| `col_03` 状態区分 | 対応表: `'1'` 受付 → `ACCEPTED`、`'2'` 引当済 → `ALLOCATED`、`'3'` 出荷済 → `SHIPPED`、`'9'` 取消 → `CANCELLED`。対応表は domain のコードに持ち、変えるときはこの ADR を改訂する | `UNKNOWN_STATUS_CODE` |
| `col_06` 受注金額(`NUMERIC(13,2)`。通貨の列はない) | 通貨は **JPY と明示する**(暗黙にしない。ADR-0011)。小数の文字列を正確に解析し、最小通貨単位(円)の `minorUnits` にする。JPY の小数桁は 0 なので、小数部は 0 でなければならない(丸めない) | 小数部が 0 でない: `AMOUNT_HAS_FRACTION`。負、または 9,999,999,999 円を超える: `AMOUNT_OUT_OF_RANGE` |
| 文字列の全般 | 置換文字(U+FFFD)、または C0 / C1 の制御文字を含むものは文字化けとみなす | `MALFORMED_TEXT` |
- 金額の上限(9,999,999,999 円)は、1 件の受注の業務上の上限として契約に書く(レガシーの列は 99,999,999,999.99 まで入る。それを超える値は運用の誤りの印として扱う)。

### 9. 出力の契約(INT-SALES-003。Contract First)
- `sales.legacy-order.changed.v1`(`contracts/asyncapi/legacy-order-events.v1.yaml`・`contracts/avro/sales/LegacyOrderChanged.avsc`)。State Transfer Event(Framework 6.2)で、レガシーの表・列・コード値は運ばない。
- compacted(`cleanup.policy=compact`)。キーは注文番号。削除は値のない tombstone。tombstone は `delete.retention.ms`(1 日)の間だけ残る。**1 日より長く止まった消費者は、トピックの最初から読み直して状態を作り直す**(読み直して現れなかったキーを消す)。この条件は AsyncAPI の契約に書いた。
- ヘッダは CloudEvents binary mode(`ce_source=/sales/legacy-order-acl`・`ce_type=sales.legacy-order.changed`)と `traceparent`・`correlationid`。レガシーはトレースを持たないため、ACL が変更ごとにトレースと correlationid を始める(CONSUMER の span の下に PRODUCER の span)。tombstone にも同じヘッダを付ける。
- `source { lsn, committedAt, snapshot }` を運ぶ(§6 の比較、⑥ の照合)。

### 10. 監視(⑤b)
- Grafana(CDC のダッシュボードに追加): ACL の Consumer Group の lag(kafka-exporter)、変換の結果ごとの件数、DLQ の原因ごとの件数。
- アラートの候補(promtool のテストを付け、P06 ④ と同じく `and on(instance) (up == 1)` で exporter が動いている間だけ判定する):
  - DLQ のトピックのオフセットが増えた(kafka-exporter。ACL が止まっていても判定できる)
  - ACL の lag が閾値を超えた状態が続く
  - ACL のメトリクスがない(`absent()`)
  - レガシーのスロット `legacy_juchu` の WAL の保持(P06 ④ のルールが注文のスロットに限られていれば広げる)

### 11. 範囲
- ⑤ で扱うのは、受注の **ヘッダの表(t_juchu)だけ**。明細の表との結合は、2 つの表をまたぐ変更を結合するために ACL が状態を持つ必要があり、別に扱う(#82。状態ストア・変更の順序・片方だけ届いた場合を検討する)。

## Alternatives Considered
- **ACL を legacy-sim の中に置く**: legacy-sim は「改修できないレガシー」の模擬で、変換の知識を持たせると、レガシーを改修したことになる。不採用。
- **Connect の SMT(独自の Transform)で変換する**: 業務の変換の規則が Connect のプラグインになり、domain の単体テストができない。ソースのコネクタには DLQ がなく(`errors.deadletterqueue.*` はシンクのコネクタだけ)、変換できないレコードを隔離できない(`errors.tolerance=all` は黙って捨てる)。不採用。
- **生のキーを主キーのままにして、ACL が主キー → 注文番号の状態を持つ**: ACL に状態ストアが要り、削除と登録し直しの順序の問題(§5)も残る。不採用。
- **Exactly-Once**: §6 の比較のとおり。上流が At-Least-Once のため全体の利点が小さく、消費者に `read_committed` を求める。不採用。
- **生の CDC のスキーマも契約(contracts)に書き、schema-publish で登録する**: §4 のとおり、DB の定義と二重に持つことになり、Debezium が生成するスキーマとずれる。不採用。
- **生の CDC を JSON(スキーマなし)で出す**: Framework 6.7 のアンチパターン(スキーマなしの JSON)。DDL の互換性の検査も効かない。不採用。
- **DLQ を出力のトピックの名前(`sales.legacy-order.changed.v1.dlq`)にする**: Framework 6.5 の DLQ は、処理できなかった入力のトピックに付ける。DLQ のレコードは生のバイト列で、出力の契約の形ではない。不採用。
- **変換の誤りも 3 回リトライしてから DLQ に送る**: 決定的な誤りはリトライで変わらない。遅延が増え、後続の変更も待たされる。不採用。
- **profile `legacy` を新しく作る**: `cdc` の profile はもともと Kafka Connect と「レガシー CDC」を含む(ADR-0016 §1)。`make verify PROFILE=cdc` も legacy_sim の DB で疎通を確かめていた。`cdc` に置く。

## Consequences(トレードオフ)
- `REPLICA IDENTITY FULL` で、レガシーの表の更新・削除の WAL が増える。
- レガシーの DDL の変更が FULL 互換でないと、取り込みが止まる(§4)。止まる前に変更を確かめる手順(⑥ の Runbook)が要る。
- 再送で、消費者は一時的に少し前の状態を見ることがある(§6)。消費者は冪等な Upsert で作り、必要なら `source.lsn` で比べる。
- DLQ に入った変更は、DLQ から戻さず、部分の再同期で回復する(§7)。回復するまで、その注文の出力は古い状態のまま(または出力されない)。
- ACL は状態を持たないため、明細との結合(#82)はこの方式のままではできない。

## 改訂履歴
- 2026-10-08: P06 ⑤a で作成。legacy-sim(V1・V2・simulate)、コネクタ legacy-juchu、契約(INT-SALES-003)を入れた。`LegacyCdcIT` で Snapshot・c / u / d・削除の変更前の値・キーの列の変更(削除 + 登録)・Incremental Snapshot・グループ cdc-raw・Debezium の権限を確かめた。ACL(§5〜§10 の実装)は ⑤b。
