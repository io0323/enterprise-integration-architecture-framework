# ADR-0029: 注文 Saga(Orchestration)・補償・期限・取消済みの印
- Status: Accepted
- Date: 2026-10-09
- Framework 参照章: 4.2, 6.2, 6.3, 13.1, 13.2, 13.3

## Context
Framework は複数のシステムにまたがる業務の更新に Saga を求め、2PC を禁じる(4.2・4.4・13.3)。ROADMAP P07 は、注文の受付の後の在庫の引当 → 決済 → 出荷を
**Orchestration 方式**(order-service が状態を持つ)で行い、補償を含め、状態を saga テーブルで持つことを求める。DoD は、在庫不足・決済の失敗・タイムアウトで補償が走ること。
コマンドの通り道は ADR-0006(Kafka のコマンドのトピック)、受信の部品と冪等消費は ADR-0028 で決めた。次の点が決まっていなかった。
- コマンドと返信の契約(キー・項目・互換性の進め方)。
- 補償の順序と、取り消せない段(出荷済み)の扱い。
- タイムアウトの検出(どの時計で、誰が)と、期限切れの後に遅れて届く結果・コマンドの扱い。
- 補償のコマンドが元のコマンドより先に届いたとき(別のトピックの間では順序が保証されない)の扱いと、その印をいつまで残すか。

## Decision
### 1. Orchestrator と Saga の単位
- Orchestrator は order-service。注文 1 件に Saga 1 つ。Saga の状態は order の DB の `order_saga` 表に持つ(Saga ID・注文 ID・状態・補償の理由・段の期限・送り直しの回数・版)。
- **Saga ID は注文 ID と別の UUIDv7** にする。すべてのコマンドと返信のキー(パーティションキー)で、参加者の業務キーの冪等の単位(§5)。
  注文の取消の Saga(出荷の前の利用者の取消。P07 の対象外)など、1 つの注文に別の Saga ができても、参加者の記録が混ざらないようにする。
- 注文の受付(`PlaceOrder`)の **同じトランザクション** で、注文・Saga(`RESERVING_STOCK`)・`sales.order.created.v1`・最初のコマンド(在庫の引当)を書く。
  コマンドの発行は Outbox + Debezium(ADR-0007)。以降も、返信の処理(または期限切れの処理)の 1 つのトランザクションで、Saga と注文の状態・冪等消費の記録・次のコマンドを書く。

### 2. 通り道(契約)
| 連携 ID | 種類 | トピック | 送信 → 受信(Consumer Group) | 機密区分 |
|---|---|---|---|---|
| INT-INVENTORY-001 | コマンド(queue) | `inventory.stock.cmd-reserve.v1`・`cmd-release.v1` | order → inventory(`inventory.command`) | internal |
| INT-INVENTORY-002 | 返信(pub-sub) | `inventory.stock.reserved.v1`・`reservation-rejected.v1`・`released.v1` | inventory → order(`order.saga`) | internal |
| INT-PAYMENT-001 | コマンド | `payment.payment.cmd-authorize.v1`・`cmd-void.v1` | order → payment(`payment.command`) | confidential(顧客 ID・金額) |
| INT-PAYMENT-002 | 返信 | `payment.payment.authorized.v1`・`declined.v1`・`voided.v1` | payment → order | internal |
| INT-SHIPPING-001 | コマンド | `shipping.shipment.cmd-arrange.v1`・`cmd-cancel.v1` | order → shipping(`shipping.command`) | confidential(届け先の住所) |
| INT-SHIPPING-002 | 返信 | `shipping.shipment.shipped.v1`・`rejected.v1`・`cancelled.v1` | shipping → order | internal |

- 契約は `contracts/asyncapi/{inventory,payment,shipping}-{commands,events}.v1.yaml` と `contracts/avro/{inventory,payment,shipping}/`。カタログは P07 ③ では `design`、参加者と Orchestrator を入れたフェーズ(④・⑤)で `active` にする。
- 参加者も返信を Outbox + Debezium で発行する(業務の更新と返信を 1 つのトランザクションにする。二重書き込みの禁止)。
- Orchestration では inventory は `sales.order.created.v1` を購読しない。INT-SALES-002 の consumer から `inventory.reservation` を外した。

### 3. 状態遷移と補償
遷移表と図は `docs/architecture/order-saga.md`(domain の `OrderSagaRules` と照合する)。
- 前進: `RESERVING_STOCK` → `AUTHORIZING_PAYMENT` → `ARRANGING_SHIPMENT`(注文 `CONFIRMED`)→ `COMPLETED`(注文 `SHIPPED`)。
- **補償は前進の逆の順に、1 つずつ** 行う: `CANCELLING_SHIPMENT` → `VOIDING_PAYMENT` → `RELEASING_STOCK` → `COMPENSATED`(注文 `CANCELLED`。`sales.order.cancelled.v1`)。前の補償の結果を受け取ってから次を送る。
  - 出荷は取り消せないことがある(出荷済み)。出荷の取消の結果を待ってから決済を取り消すことで、「出荷したのに代金を取り消した」状態を作らない。
    取消の結果が `ALREADY_SHIPPED` なら、補償をやめて `COMPLETED` に進む(出荷が Saga の中で後戻りできない段であることの扱い)。
  - 決済の承認の取消と在庫の解放は、相手が何も持っていなくても成功で答える(`NOT_AUTHORIZED` / `NOT_RESERVED`)。
- 補償に入るきっかけ: 拒否の返信(在庫不足 → 補償なしで `COMPENSATED`、決済の拒否 → 在庫の解放から、出荷の拒否 → 決済の取消から)と、**各段の期限切れ**。
  期限切れでは相手が処理したかどうか分からないので、その段の補償から始める(引当の期限切れ → 解放、承認の期限切れ → 承認の取消、出荷の期限切れ → 出荷の取消)。
- **補償の段の期限切れは、同じコマンドを送り直す**(回数の上限なし。補償は必ず終える)。送り直しの回数が 5 回を超えたら、メトリクスとアラートで運用者に知らせる(⑤)。
  補償の失敗を DLQ や手作業に回すと、在庫・与信が残ったままになるため。
- 表にない組み合わせ(重複・期限切れの後に遅れて届いた前の段の結果・終端の後の結果)は無視する。返信の受信で、未知の値(`outcome` / `reason` の `UNKNOWN`)は判定できないので DLQ に送る。
- 同じ Saga に返信の処理と期限切れの処理が同時に来ることがある。どちらも Saga の行を `SELECT ... FOR UPDATE` で読んでから判定するので、後の側は先の確定を待ち、新しい状態で判定し直す(多くは無視になる)。

### 4. 契約の約束
- キーは Saga ID。返信は Saga ID・注文 ID と、結果(`outcome`)または拒否の理由(`reason`)だけを運ぶ(参加者の内部の情報や、個人情報を返さない)。
- `reason` / `outcome` の enum には **既定の値 `UNKNOWN`** を付ける(Avro の enum の `default`)。新しい版で値を増やしても、古い読み手は `UNKNOWN` として読める(FULL 互換。ADR-0014)。
  送り手は `UNKNOWN` を使わない。読み手は `UNKNOWN` を受け取ったら DLQ に送る(判定を推測しない)。
- 金額と住所の形は、注文の契約(`io.eia.events.common.Money` / `Address`)と同じ。Avro のファイルごとに定義する(参照を使わない。ADR-0025)。

### 5. 参加者の冪等と「取消済み」の印
- 参加者(inventory / payment / shipping)は、`ce_id` の冪等(processed_message。ADR-0028 §3)に加えて、**Saga ID を業務キーにした行**(引当・与信・出荷の記録。1 Saga に 1 行)で冪等にする。
  - 同じ Saga ID の 2 回目以降のコマンド(Orchestrator の送り直し・作り直し)には、記録した最初の結果を返し直す(返信は新しい `ce_id`。Orchestrator は状態で判定するので、重複は無視される)。
- **補償のコマンドが先に届いたとき**(例: 引当の期限切れで解放を送ったが、引当より先に解放が処理された)は、その Saga ID の行を「取消済み」(印)として作り、
  `NOT_RESERVED` / `NOT_AUTHORIZED` / `NOT_ARRANGED` で答える。後から届いた元のコマンドは、印を見て拒否する(`ALREADY_RELEASED` / `ALREADY_VOIDED` / `ALREADY_CANCELLED`)。
  これで、在庫・与信・出荷が、補償の後に残らない。
- **印の保持期間は 30 日**(既定。設定で変えられるが、14 日より短くは起動時に拒否する)。根拠:
  - 元のコマンドが参加者に届きうる最も遅い時点は、コマンドのトピックの保持期間(7 日。Consumer が止まっていた・遅れていた)と、
    DLQ の保持期間(7 日。DLQ に入ったコマンドを、保持期間の終わりに Replay する。ADR-0028 §6)を足した **14 日** まで。
  - 印がそれより先に消えると、遅れて届いた元のコマンドを初めてのものとして処理し、補償の後に在庫・与信・出荷が残る。
  - **processed_message(14 日。ADR-0028 §3)との関係**: processed_message は「同じ `ce_id` の 2 回目」を捨てる。遅れて届いた元のコマンドは、参加者がまだ処理していない(初めての `ce_id`)ので、
    processed_message では止められない。止めるのは印だけである。このため印の保持期間は processed_message に頼らずに決め、上の 14 日以上とする。
    結果として processed_message の保持期間(14 日)以上になり、processed_message が捨てる重複も、印の側でも拒否できる(二重の守り)。
  - 14 日に、トピック・DLQ の保持期間の運用での延長と、調査の時間の余裕を足して 30 日とする。トピックや DLQ の保持期間を延ばすときは、この値も見直す。
- 印・記録の削除は参加者の定期のジョブが行う。消すのは終端の状態(取消済み・解放済み・取消済みの与信・取消済みの出荷)で保持期間を過ぎた行だけで、有効な引当・与信は消さない。
  時刻は DB の時計で判定する(processed_message と同じ)。
- 確かめること(④): 保持期間の内側で遅れて届いた元のコマンドが拒否される統合テスト。同時の注文で在庫が負にならない並行の統合テスト(inventory)。

### 6. 期限(DB の時計)
- 段の期限は、その段に入るときに **DB の時計** で `deadline_at = clock_timestamp() + 段の期限` として記録する。期限切れの判定も `deadline_at <= clock_timestamp()` で行う。
  アプリのインスタンスの時計を使わない(インスタンスの時計のずれで、期限が早まったり遅れたりしないようにする。P05 の冪等のリース(ADR-0022 §3)と同じ理由)。
- 期限切れの検出は、order-service の定期のジョブが `FOR UPDATE SKIP LOCKED` で、期限を過ぎた終端でない Saga を少しずつ取り出して処理する(複数のインスタンスで二重に処理しない)。
- 段の期限の既定は、前進の段 30 秒・補償の段(送り直しの間隔)30 秒。ローカル基盤と E2E では短くする(⑤・⑥ の compose の設定)。
  前進の段の期限は、参加者の通常の処理時間(Outbox → Debezium → Kafka → 処理 → 返信)の p99 より十分に長くする(短すぎると、正常な注文を補償に回す)。

### 7. 参加者の模擬の規則(ローカル)
- inventory: 商品(SKU)ごとの在庫を持つ(初期データ)。引当は全部の明細を 1 つのトランザクションで行い、1 つでも足りなければ何も引き当てない(`INSUFFICIENT_STOCK`)。在庫は負にしない(④ で並行のテスト)。
- payment(決済の模擬): 金額が上限(設定。既定 1,000,000 円)を超えたら拒否する(`LIMIT_EXCEEDED`)。外部の決済の代行は呼ばない。
- shipping: 届け先の国が `JP` 以外なら拒否する(`UNSUPPORTED_DESTINATION`)。受けたら直ちに出荷する(模擬)。
- これらは E2E の在庫不足・決済の失敗・出荷の拒否のシナリオを作るための規則で、業務の仕様ではない。

### 8. 注文のイベント
- `COMPENSATED` で注文を `CANCELLED` にするときに `sales.order.cancelled.v1`(既存の契約)を発行する。理由は補償の理由(`STOCK_UNAVAILABLE` など。個人情報を含めない)。
- `CONFIRMED` / `SHIPPED` のイベントは、購読者がいないため P07 では追加しない(必要になるフェーズで、先に契約に追加する)。

## Alternatives Considered
- **Choreography(各サービスがイベントに反応して次を行う)**: 流れの知識が各サービスに散らばり、補償の順序・期限の管理が難しい。ROADMAP は Orchestration を指定している。不採用。
- **補償を並行に送る**: 速いが、出荷の取消と決済の取消が競合し、出荷済みなのに代金を取り消すことがある。不採用(§3)。
- **出荷の段を補償しない(出荷は送り直しだけで前に進める)**: 出荷の取消のコマンドが要らず単純だが、出荷の期限切れで注文が長く止まる。承認の時点で、出荷の取消と出荷済みの扱いを含めることにした。
- **補償の送り直しに上限を設け、尽きたら DLQ・手作業**: 在庫・与信が残ったままになる。上限を超えたら知らせ、送り直しは続ける。不採用。
- **Saga ID = 注文 ID**: 単純だが、1 つの注文に別の Saga(取消など)ができたときに、参加者の記録が混ざる(§1)。不採用。
- **アプリの時計で期限を判定する**: インスタンスの時計のずれで判定がずれる。ご指示により DB の時計にする(§6)。
- **期限切れを Kafka の遅延メッセージで起こす**: Kafka には遅延の配信がなく、リトライのトピックで作ると運用の対象が増える。DB の期限と定期のジョブにする。

## Consequences(トレードオフ)
- 補償を 1 つずつ行うため、補償の完了まで最大で「段の数 × 段の期限」かかる。注文が `CANCELLED` になるまで、利用者からは `PLACED` / `CONFIRMED` に見える。
- 参加者は Saga ID ごとの記録と印を持ち、保持期間(30 日)の間は消せない。
- 期限切れの補償は、相手が正常に処理していた場合にも走る(期限が短すぎると、正常な注文を取り消す)。期限は参加者の処理時間の p99 で決め、補償の件数を監視する(⑤)。
- order-service は Kafka の受信(`order.saga`)・期限のジョブ・Outbox を持ち、責務が増える。Saga の判定は domain の純粋な関数(`OrderSagaRules`)に閉じ込める。

## 改訂履歴
- 2026-10-09: P07 ④a で inventory-service を入れた。コマンドのトピックは、保持期間(7 日。§5 の印の保持期間の根拠)を固定するため、Debezium に作らせず `infra/local/kafka/topics.conf` で明示して作る(DLQ も同じ)。§5 の確かめること(遅れて届いたコマンドの拒否・同時の注文で在庫が負にならない)は `InventoryPersistenceIT`。
- 2026-10-09: P07 ④b で payment-service と shipping-service を入れた。データの最小化(Framework 12.2)のため、payment は顧客 ID を、shipping は届け先の国のほかの住所を記録しない(模擬は外部の決済・運送を呼ばないため)。shipping の記録は書き換えない(アプリのロールに UPDATE を付けない)ので、記録の読み取りでは行をロックしない。同じ Saga の初回が同時に来たときは主キーが 1 つに絞り、後の側は Transient のやり直しで返し直しになる(§5 の冪等は保たれる)。出荷の取消の `CANCELLED`(手配したが出荷の前)は、模擬が直ちに出荷するため返さない(契約の値としては残す)。
- 2026-10-09: P07 ⑤ で order-service に Orchestrator を入れた。
  - `order_saga` に版の列は持たない(§1 の「版」は持たない)。返信の処理と期限切れの処理は、どちらも行を `FOR UPDATE` でロックしてから判定するので、版による楽観的ロックは要らない(§3 の最後の項)。期限は `deadline_at = clock_timestamp() + make_interval(secs => 段の期限)`(マイクロ秒の精度)。終端の Saga は期限を持たない(DB の CHECK でも担保する)。アプリのロールは DELETE と、Saga ID・注文 ID の UPDATE ができない。
  - 期限切れのジョブは、50 件ずつ 1 つのトランザクションで処理し、50 件に満たなくなるまで繰り返す。確認の間隔は `ORDER_SAGA_TIMEOUT_SCAN_INTERVAL`(既定 5 秒)、段の期限は `ORDER_SAGA_STEP_TIMEOUT`、補償の送り直しの間隔は `ORDER_SAGA_COMPENSATION_INTERVAL`(いずれも既定 30 秒。ローカル基盤も 30 秒。E2E は ⑥ で短くする)、知らせるまでの送り直しの回数は `ORDER_SAGA_STALL_AFTER_RESENDS`(既定 5)。
  - 返信の冪等消費は `platform/inbox` の `processed_message`(Consumer Group `order.saga`。保持 14 日)。返信の重複は、これと Saga の状態(表にない組み合わせは無視)の二重で捨てる。知らない Saga ID の返信は NonRetryable で DLQ に送る。
  - 返信のトピック(9 つ)とその DLQ を `infra/local/kafka/topics.conf` に加え、order の profile でも作る(参加者のコネクタより先に order-service が読み始めるため)。
  - **返信の読み取りの状態は order-service の `/health/ready` に含めない**(ADR-0028 §1 の例外)。order-service の主な責務は API で、Schema Registry が止まっても注文を受け付ける(ADR-0025 §3)。返信の読み取りだけが Unavailable のときに API のトラフィックを外すと、その性質を壊す。止まりは `eia.consumer.unavailable` と `EventConsumerUnavailable`・`EventConsumerStalled` のアラートで分かる。
  - メトリクス `eia.saga.transitions{from,to,failure}`・`eia.saga.resends{state}`・`eia.saga.stalled{state}`・`eia.saga.ignored{state,signal}`。アラート `OrderSagaCompensationStalled`(`prometheus/rules/saga.rules.yml`。critical。対応は `docs/runbooks/order-saga.md`)。
  - `sales.order.cancelled.v1` の `reason` は補償の理由(`STOCK_UNAVAILABLE`・`PAYMENT_DECLINED`・`SHIPMENT_REJECTED`・`TIMED_OUT`)。
  - 確かめること: 期限が DB の時計で書かれ判定されること・SKIP LOCKED(`ExposedSagaStoreIT`)、Outbox → Debezium のコマンドと返信による正常・決済の失敗・タイムアウトの補償(`OrderSagaIT`)。
- 2026-10-10: P07 ⑥ で E2E(`SagaE2E`)とダッシュボード(Grafana の Order — Saga)を入れた。E2E の段の期限は compose と同じ 30 秒(短くしない。タイムアウトのシナリオは inventory-service を止めて作り、期限を過ぎて解放のコマンドが出ることを待つので、待ち時間は期限の長さで決まる)。CI の e2e は `make up PROFILE="order cdc saga"`。
