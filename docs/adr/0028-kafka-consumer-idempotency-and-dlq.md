# ADR-0028: Kafka の Consumer・冪等消費・リトライと DLQ
- Status: Accepted
- Date: 2026-10-09
- Framework 参照章: 6.4, 6.5, 13.1, 13.2, 14.1

## Context
P07 では、注文 Saga の参加者(inventory / payment / shipping)と Orchestrator(order)が、コマンドとイベントを Kafka から読む(ADR-0006)。
Framework は非同期の連携に「冪等 + DLQ + Replay」の 3 点セットを求める(13.3)。次の点が決まっていなかった。
- 受信の共通部品(`platform/messaging-kafka` の Consumer 側。ADR-0009 で P07 に作ると決めた)の処理の順序・コミットの時機。
- Framework 6.5 の「リトライ(既定 3 回、Backoff)後に DLQ」を、どの失敗に当てはめるか。自分の DB が止まっている間に届いたメッセージまで DLQ に送ると、回復の後に大量の Replay が要る。
- 冪等消費の記録(processed_message)の置き場所・キー・保持期間。
- #80(スロットが無効になった期間のイベントの作り直し)で、作り直したイベントの `ce_id` が元と変わる場合の重複の扱い。

P06 ⑤b の legacy-order-acl は、同じ役割の読み取りのループ(`LegacyChangeConsumer`)を個別に持っていた(共通部品への移行は #88。改訂履歴)。

## Decision
### 1. Consumer(`EventConsumer`)
- 購読するトピックごとに `EventSubscription`(トピック・値の型の deserializer・連携 ID・処理)を渡す。1 つの Consumer Group で複数のトピックを購読してよい(例: Orchestrator は参加者の返信のトピックをまとめて読む)。
- **At-Least-Once**: 処理(業務の更新と冪等消費の記録の確定)の後にオフセットをコミットする(`enable.auto.commit=false`)。コミットの前に落ちれば、最後のコミットの位置から送り直す。
- **順序**: 1 つの Consumer(1 つのスレッド)で、パーティションごとに届いた順に 1 件ずつ処理する。同じキー(集約の ID・Saga ID)の順序が保たれる。並列度はパーティション数(サービスのインスタンス数)で上げる。
- コミットの失敗(処理が長くグループから外された・リバランスの途中)は記録して続ける。新しい割り当て先が送り直し、重複は §3 で吸収する。
- ヘッダ(CloudEvents)は受信時に `EventMetadata.fromHeaders` で検査する。値は `AvroEventDeserializer`(書き手のスキーマを contentId から取得。ADR-0025 §1)で読む。
- `ready`(サービスの `/health/ready` に使う)は、読み取りを始めていて、直近の処理が §2 の Unavailable でないこと。パーティションの割り当ては条件にしない。
- Consumer Group の名前は `{service}.{purpose}`(INTEGRATION_STANDARDS §1)。コマンドのトピックは受信サービスの 1 グループだけ(ADR-0006)。

### 2. 失敗の扱い
処理(`EventHandler`)は成功(`Handled.PROCESSED` / `Handled.DUPLICATE`)か、次の 3 種類の失敗(`HandlingFailure`)を返す。

| 種類 | 例 | 扱い | DLQ の `eiaf.dlq.attempts` |
|---|---|---|---|
| Rejected | 契約違反・業務の検証の失敗(Framework 13.2 の「4xx 系・契約違反」) | リトライせずに DLQ | 1 |
| Transient | 楽観的ロックの衝突・直列化の失敗など、**そのメッセージの処理だけ** の一時的な失敗 | その場でリトライ(初回 + 3 回。Exponential Backoff + Full Jitter。INTEGRATION_STANDARDS §3)し、尽きたら DLQ | 4 |
| Unavailable | 自分の DB・Schema Registry が使えない(**全部のメッセージが同じく失敗する**) | DLQ に送らず、処理を終えた分までをコミットし、未処理の位置から Backoff(最大 30 秒)の後に読み直し続ける。その間 `ready=false` | — |

- 受信の部品が判定するもの: ヘッダの欠落・不正(`INVALID_HEADERS`)・Avro として読めない(`UNDECODABLE`)・想定しない tombstone(`UNEXPECTED_TOMBSTONE`)は Rejected。Schema Registry の一時的な失敗は Unavailable。
- 処理が想定しない例外を投げたら(実装の誤り)、Transient として扱う(`UNEXPECTED_EXCEPTION`)。リトライが尽きれば DLQ に隔離し、本流を止めない。例外のメッセージは値を含みうるため、型の名前だけを残す。
- DLQ に送れないときは、Unavailable と同じく読み直す(DLQ に入る前にオフセットを進めない)。
- DLQ の形式は ADR-0026 §7 と同じ(`DeadLetterPublisher`。キーと値は受け取ったバイト列のまま。`eiaf.dlq.*` のヘッダ。INTEGRATION_STANDARDS §2)。`eiaf.dlq.reason` は処理が返したコードを大文字にしたもの。
- **Unavailable を DLQ に送らない理由**: 障害の間に届いたメッセージは処理できないのではなく、まだ処理していないだけである。DLQ に送ると、回復の後に全件の Replay が要り、その間に届いた後続のメッセージと順序が入れ替わる。読み直し続ければ、回復と同時に順に処理される。
- **Unavailable の間の検知**: 処理が止まるので、Consumer Group の lag が増える。lag のアラート(P07 ②。kafka-exporter の `kafka_consumergroup_lag`)が拾うことを promtool のテストで確かめる。`eia.consumer.unavailable` のメトリクスと `/health/ready` の 503 でも分かる。
- Transient のリトライはその場で待つ(後続のメッセージは待たされる)。リトライ用のトピック(遅延キュー)に逃がす方式は、同じキーの順序が崩れるため採らない(下の代替案)。待ちの合計は既定で数秒で、`max.poll.interval.ms`(既定 5 分)より十分に短い。

### 3. 冪等消費(`platform/inbox`)
- 受信側は、業務の更新と **同じトランザクション** で `inbox.processed_message` に `(consumer_group, message_id = ce_id)` を `INSERT ... ON CONFLICT DO NOTHING` で記録する。挿入できなければ重複(`Handled.DUPLICATE`)として業務の処理をしない。業務がロールバックすれば記録も残らない。
  - 同じメッセージを並行に受け取っても(リバランスの直後など)、後の側は先の確定を待って重複になる(主キーの一意性。`InboxIT` で確かめる)。
- 置き場所は新しいモジュール `platform/inbox` にする(Outbox の対になる部品)。`platform/messaging-kafka` に置くと、Kafka の部品が Exposed と JDBC に依存する。`platform/inbox` は Kafka にも他の platform にも依存しない(メッセージの ID とトピック名だけを受け取る)。
- 表は各サービスの DB のスキーマ `inbox` に、`InboxSchema.migrate`(所有者のロール。専用の履歴の表 `inbox.inbox_schema_history`)で作る。アプリのロールには INSERT・DELETE と、条件に使う列の SELECT だけを付ける。
- **時刻は DB の時計**(`clock_timestamp()`)で記録し、削除の判定も DB の時計で行う。インスタンスの時計のずれで保持期間がずれないようにする。
- **保持期間は 14 日**(`Inbox.DEFAULT_RETENTION`)。同じ `ce_id` のメッセージが再び届きうる期間より長くする:
  - 業務イベント・コマンドのトピックの保持期間(7 日。Framework 6.2)の間は、オフセットの巻き戻し(Framework 13.1 の Replay)で同じメッセージが届きうる。
  - DLQ の保持期間(7 日)の間は、DLQ からの Replay で同じメッセージが届きうる。Replay した後に、同じ DLQ をもう一度 Replay する誤りもありうる。
  - 長い方(7 日)に余裕(7 日)を足して 14 日とする。トピックや DLQ の保持期間を延ばすときは、この値も見直す。
  - 削除はサービスが定期的に `Inbox.purgeExpired`(古い順に 1000 件ずつ)で行う。
- **業務キーでの冪等**: `ce_id` が同じ重複は processed_message で捨てる。`ce_id` が変わる重複(#80 のイベントの作り直し・Orchestrator の補償の再送など)は、受信側の業務の表の一意性(Saga ID など)で吸収する。processed_message だけで業務キーの重複を判定する方式(キーを `{注文 ID}:{ce_type}` にするなど)は採らない。作り直しの有無を受信の部品が知る必要があり、同じキーの正当な 2 回目(取消の後の再注文など)と区別できないため。Saga の参加者の業務キーは ADR-0029 で決める。

### 4. 追跡
- 1 件ごとに CONSUMER の span(`{topic} process`)を作り、ヘッダの `traceparent` の子にする(`EiaTraceContextPropagator`。ADR-0018)。Correlation ID はヘッダの `correlationid` を引き継ぐ(ヘッダが不正なら採番する)。ログの `integration_id` は購読の連携 ID。
- 処理の中の発行(Outbox の `OutboxEvents.create`)と DLQ への送信は、この span の子になる。Saga のコマンドと返信は、注文の受付からの 1 つのトレースでつながる。
- span の属性は OTel のメッセージングの意味規約(`messaging.system`・`messaging.destination.name`・`messaging.consumer.group.name`・`messaging.operation.type=process`・`messaging.message.id`・パーティション・オフセット)。値は入れない。

### 5. メトリクス
`ConsumerMetrics`(OTLP → Prometheus): `eia.consumer.messages{outcome=processed|duplicate|dead_lettered}`・`eia.consumer.dead_letters{reason}`・`eia.consumer.retries{error.code}`・`eia.consumer.unavailable{error.code}`・`eia.consumer.process.duration`。どれもトピックと Consumer Group の属性を持つ。lag と DLQ の滞留は Kafka 側(kafka-exporter)で見る。

アラート(`infra/local/prometheus/rules/consumer.rules.yml`。promtool のテストは `consumer.test.yml`。対応は `docs/runbooks/event-dlq-replay.md`):

| アラート | 条件 | 主に拾うもの |
|---|---|---|
| `EventDeadLetters` | DLQ のトピック(`_cdc.*` を除く)のオフセットが 10 分の間に増えた | Rejected・リトライの尽き |
| `EventConsumerStalled` | lag があるのに、コミットしたオフセットが 10 分変わらない(2 分続く) | Unavailable で読み直し続けている間・Consumer の停止 |
| `EventConsumerUnavailable` | アプリの `eia_consumer_unavailable_total` が 5 分の間に増えた状態が 5 分続く | Unavailable(まだ一度もコミットしていないグループも含む) |
| `EventConsumerLagHigh` | lag が 1,000 件を超えた状態が 10 分続く(legacy-order-acl は `acl.rules.yml`) | 処理が追いつかない |

- Unavailable の間は、§2 のとおりオフセットをコミットしない。その間に届いたメッセージで lag が増え、オフセットは変わらないので `EventConsumerStalled` が拾う。promtool のテストで、処理が止まってから警告し、回復すれば解消することを確かめる。lag の件数の閾値だけでは、量の少ない連携(Saga のコマンドなど)で止まっても閾値に届かないため、オフセットが進まないことで判定する。
- kafka-exporter は、オフセットをコミットしたことのないグループのオフセットを出さない。新しいグループが最初から Unavailable の場合は、アプリのメトリクスの `EventConsumerUnavailable` で拾う。

### 6. Replay(`DeadLetterReplayer`・`make dlq-replay`)
- DLQ のメッセージを、原因を除いた後に元のトピックへ戻す(Framework 13.1)。部品は `platform/messaging-kafka` の `DeadLetterReplayer`、CLI は `tools/dlq-replay`(`make dlq-replay ARGS="..."`)。手順は `docs/runbooks/event-dlq-replay.md`。
- **既定は dry-run**(対象の一覧だけ)。送るのは `--execute` を付けたときだけ。**件数の上限 `--limit`(1〜1000)は必須**。条件は原因(`eiaf.dlq.reason`)・`ce_type`・キー・DLQ のパーティションとオフセットの範囲・DLQ に入った時刻・一度戻したことがあるか。
- 読む範囲は、始めた時点の DLQ の末尾まで(実行中に DLQ に入ったものは含めない)。Consumer Group を使わずに読み、オフセットをコミットしない(DLQ は消さない。保持期間で消える)。
- 戻すメッセージは、キー・値・`ce_id` を含むヘッダを DLQ のまま使い、`eiaf.dlq.*` を外して `eiaf.replay.*`(DLQ の位置・原因・戻した時刻。INTEGRATION_STANDARDS §2)を付ける。**`ce_id` を変えないので、同じものを 2 回戻しても受信側は 1 回だけ処理する**(§3。保持期間 14 日の根拠の 1 つ)。パーティションはキーで決まり、元のメッセージと同じパーティションに入る。
- 戻したメッセージが再び失敗すれば、`eiaf.replay.*` が付いたまま DLQ に入る(`DeadLetterPublisher` は `eiaf.dlq.*` だけを置き換える)。一覧の `replayed_before` で分かる。
- **生の CDC の DLQ(`_cdc.*`)は拒否する**。後から届いた変更より古い状態で上書きするため、signal 表の部分の再同期で回復する(ADR-0026 §7)。DLQ の記録の元のトピックが DLQ の名前と合わないものも戻さない。
- 順序: 戻したメッセージは、隔離の後に届いた同じキーのメッセージより後に処理される。受信側は業務キーで冪等で、状態の遷移を検査するので、古い指示は拒否されるか何もしない。業務として正しいかは、Runbook の手順でキーごとに確かめる。
- CLI の終了コード: 0 = 終えた、1 = 戻せなかったものがある(もう一度実行してよい)、2 = 実行できない。出力に値は出さない。Kafka のクライアントは `platform/messaging-kafka` の中だけで使う(Konsist。CLI は `DeadLetterReplayer.connect` を呼ぶだけ)。

## Alternatives Considered
- **Spring Kafka / Kafka Streams などのフレームワーク**: リトライ・DLQ の仕組みを持つが、本リポジトリの技術スタック(Ktor・Koin・kotlinx.coroutines。CLAUDE.md §2)と合わず、Spring の依存を持ち込む。Kafka Streams の Exactly-Once は Kafka の中だけで、業務の DB の更新と一体にならない。不採用。
- **リトライ用のトピック(遅延キュー。`{topic}.retry-1` など)で非同期にリトライする**: 後続のメッセージを止めずに済むが、同じキーのメッセージの順序が崩れる(Saga のコマンドは Saga ID の順序を前提にする。ADR-0006)。トピックとグループも増える。不採用。
- **Unavailable も N 回のリトライの後に DLQ に送る**: 実装は単純だが、障害の間のメッセージがすべて DLQ に入る(§2 の理由)。不採用。
- **processed_message を `platform/messaging-kafka` に置く**: §3 の理由で不採用。
- **Kafka のトランザクション(Exactly-Once)で重複を防ぐ**: 読み取り・書き込みが Kafka の中で閉じる場合だけに効き、業務の DB の更新とは一体にならない(Framework 13.2 は Exactly-Once を単一基盤内に限る)。不採用。
- **Share Group(KIP-932)**: ADR-0006 の再評価のとおり不採用のまま(DLQ と順序の理由)。

## Consequences(トレードオフ)
- 受信側の各サービスは、自分の DB に `inbox` のスキーマを持ち、業務の更新と同じトランザクションで記録する。記録の行が増えるので、定期的な削除が要る。
- Transient のリトライの間と Unavailable の間は、同じパーティションの後続のメッセージが待たされる(順序と引き換え)。Unavailable が長引けば lag のアラートになる。
- 保持期間(14 日)より後に同じ `ce_id` のメッセージが届くと、重複として捨てられない。トピック・DLQ の保持期間を延ばすときは見直す。業務キーの冪等(§3)が二重の守りになる。
- legacy-order-acl の読み取りのループは、#88 で共通部品に移した(改訂履歴)。

## 改訂履歴
- 2026-10-09: P07 ② で、§5 にアラートを、§6 に Replay を加えた。
- 2026-10-10: #88 で、legacy-order-acl の読み取りのループ(`LegacyChangeConsumer`)を `EventConsumer` に移した。以前のループはオフセットのコミットの例外を捕まえず、Kafka の停止(コミットの `TimeoutException`)やリバランス(`CommitFailedException`)で読み取りのスレッドが終わっていた。
  - **外部のトピックの購読(`ExternalSubscription`)** を加えた。CloudEvents のヘッダを持たないトピック(レガシーの生の CDC。ADR-0026)を、ヘッダを検査せずに読み、レコードごとに新しいトレースと Correlation ID を始める。処理(`RecordHandler`)は `ConsumedRecord`(キーは受け取ったバイト列のまま)を受け取る。失敗の扱い(§2)・コミット・読み直し・メトリクスは `EventSubscription` と同じ。冪等消費の記録(§3)はない(ce_id がない。処理の側で、重複しても結果が同じになるようにする)。命名規約の外のトピック名を受け付け、DLQ(`.dlq`)は購読できない。
  - 値がない(tombstone)レコードは、以前は ACL が `UNDECODABLE` で DLQ に送っていたが、`UNEXPECTED_TOMBSTONE` になる(Debezium のコネクタは `tombstones.on.delete=false` なので、実際には届かない)。
  - ACL の DLQ と読み直しの件数は、`eia.acl.dead_letters`・`eia.acl.retries` から `eia.consumer.dead_letters`・`eia.consumer.unavailable`(Consumer Group `legacy-order-acl.translate`)に移した。`eia.acl.records` は変換して発行した件数(`upserted`・`deleted`)だけを数える。ダッシュボード(CDC — Legacy)を合わせて直した。アラート(`acl.rules.yml`)は kafka-exporter のメトリクスで判定しているので変わらない。
  - Kafka の停止と再開(コミットの途中・読み取りの最中)とリバランスで読み取りが止まらないことを、`LegacyOrderAclIT` で確かめる。
