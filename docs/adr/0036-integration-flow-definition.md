# ADR-0036: iPaaS 風の Flow の定義
- Status: Accepted
- Date: 2026-10-21
- Framework 参照章: 10, 15, 16

## Context
ROADMAP P10 は、宣言的な Flow(trigger → map → route → deliver)の最小の実装と、Canonical Model を経由するマッピングを求め、DoD に「Flow の定義だけで新しいマッピングを追加できる」「Flow の定義のミスを起動時に検出する」を置く。
Framework 10 章のアンチパターンは、iPaaS の中に業務のロジックを書くこと(Smart Pipe 化)と、SaaS 同士を直接つなぐマッピング。iPaaS は Cloud Agnostic の例外になりやすく、「ロジックは Flow の定義だけ、変換はマッピングの定義だけ」に限ると移行しやすさを保てる(10.1)。

## Decision
### 1. 置き場所と検証
- Flow の定義は **`contracts/flows/{id}.v{version}.flow.yaml`** に置く(契約と同じくレビューし、マッピングの仕様を Excel にだけ置かない。Framework 15 のアンチパターン)。
- 形式は **`contracts/flows/flow.v1.schema.json`**(JSON Schema)。
  - **integration-flow は起動時にこのスキーマで検証し、誤りがあれば起動しない**(ROADMAP P10)。
  - **contract-check** も PR の時点で検査する: スキーマとファイル名(`CC-FLOW-001`)、参照(`CC-FLOW-002`)。
    参照の検査は、カタログの連携・トピック・対応表の存在と、mapping が Canonical Model の項目だけを書き、必須の項目をすべて書いていること。

### 2. Flow でできること(これだけ)
| 段 | できること |
|---|---|
| trigger | 社内の取り込みのトピックのイベント(`kind: event`。Webhook。送り手と種類で選ぶ)と、SaaS の API の定期の取得(`kind: poll`。Connector の連携 ID と対象) |
| ordering | 新旧の判定のキー(Canonical の項目)と版(元のレコードの場所)。キーごとに最新の版を記録し、古い版は出さない |
| map | Canonical の項目ごとに、元のレコードの場所(`$.a.b`。添字・フィルタ・関数は使えない)と、決まった変換の一覧(copy・trim・lowercase・uppercase・iso-instant・epoch-millis-instant・lookup(対応表)・constant)。入れ子の型は fields で書く |
| route | Canonical の項目の **等号の条件だけ**。上から順に最初に一致した route、最後は条件のない既定の route |
| deliver | 連携(integration)の channels にあるトピック |

- **任意のスクリプト・式・条件の組み合わせは書けない**(スキーマにない)。業務の判断(例: 与信の判定・値の計算)を Flow に書かない。
- **足りない変換は、Flow ではなくコードの側に足す**: 変換の一覧(スキーマの enum と integration-flow の実装)に足すか、Canonical Model の変換に足す。
  一覧に足す変換は、値の形を変えるだけのもの(業務の判断を含まないもの)に限る。Flow を書く人が「スクリプトが書ければ」と感じたら、それはコードの側の変更の合図とする。
- **マッピングは必ず Canonical Model を経由する**(`canonical` に Canonical の型を書く)。SaaS の項目から別の SaaS の項目へ、型として直接つなげない(Framework 10.2)。

### 3. 新旧の判定の記録
- Webhook の経路と定期の同期の経路は、**同じ記録**(Flow とキーごとの最新の版)を使う(ADR-0035 §4)。
  Webhook で新しい版を受けた後に、定期の同期が古い版で上書きしない。
- 記録より古い版・同じ版は出さずに数える(メトリクス)。

### 4. 取り込みの冪等
- 取り込みのトピックの購読は、platform/inbox(`processed_message`)で冪等にする(ADR-0028)。キーは CloudEvents の `ce_id`。
  `ce_id` は webhook-receiver の Outbox が受け取りの記録ごとに 1 つ作る(UUIDv7)。同じ webhook-id の再送は記録しないので、1 つの Webhook の `ce_id` は 1 つになる(Debezium の再送は同じ `ce_id`)。

### 5. 最初の Flow
- `crm-customer`: CRM の顧客を Canonical の `sales.Customer` に変えて、`sales.customer.changed.v1`(INT-SALES-007)に出す。
  trigger は Webhook(`customer.created`・`customer.updated`)と、CRM の API の定期の取得(15 分ごと)。
- 削除(`customer.deleted`)は、Canonical に削除のイベントがないので取り込まない(取り込まない種類として数える)。要るときに Canonical とイベントの契約を足す。

## Alternatives Considered
| 案 | 採らない理由 |
|---|---|
| Flow に式言語(JSONata・CEL など)やスクリプトを書けるようにする | 業務のロジックが Flow に流れ込む(Framework 10.4 のアンチパターン)。移行しやすさも失う |
| Flow の定義をサービスの中(resources)に置く | 契約と同じレビュー・検査の対象にならない。マッピングの仕様の置き場所が分散する |
| 市販の iPaaS・OSS の統合基盤(Camel など)を使う | Cloud Agnostic・ローカルで完結の方針(CLAUDE.md §1)と、Flow でできることを絞る目的に対して大きすぎる。Framework 10.3 の「変換中心なら iPaaS」の考え方を、最小の自作で実証する |
| SaaS ごとに変換をコードで書く | 新しいマッピングのたびにコードの変更とリリースが要る(ROADMAP P10 の DoD を満たさない) |

## Consequences(トレードオフ)
- 変換の一覧が足りないと、Flow で書けないマッピングが出る。そのときはコードの側(一覧か Canonical の変換)を変える必要があり、Flow の定義だけでは済まない。
- 振り分けは等号だけなので、複雑な振り分けは別の Flow に分けるか、イベントの消費者の側で行う(1 Flow = 1 連携の目的。Framework 10.2)。
- Canonical の型が増えたら、スキーマの `canonical` の enum と、contract-check・integration-flow の対応表に足す。

## 改訂履歴
- 2026-10-21: P10 ①(#13)で作成した。
- 2026-10-21: P10 ③ で §4 を直した。`ce_id` は Webhook のイベント ID ではなく、受け取りの記録ごとに Outbox が作る UUIDv7(Outbox の `id` 列と同じ値。ADR-0007)。webhook-id は UUID とは限らないため。重複の判定の効果は同じ(受け口が webhook-id で重複を止める)。
- 2026-10-21: P10 ④ で integration-flow の中核を実装した。domain と application は JSON・Canonical Model に依存しない(元のレコードは domain の値の木 `SourceValue`、写した結果は `MappedRecord`。Canonical の型への変換は adapters の `CanonicalTypes` が `CanonicalCodec.decode` で行う。ADR-0010 Decision 7)。起動時の検証は §1 の JSON Schema に加えて、ファイル名・振り分けの形・lookup の対応表・Canonical の項目(contract-check の CC-FLOW-002 の Canonical の部分と同じ)を確かめ、すべてのファイルの誤りをまとめて返す。カタログの参照(連携・トピック)は、実行時にカタログを持たないので contract-check だけが確かめる。Flow の定義はイメージに含めた contracts/flows を既定とし、`INTEGRATION_FLOW_FLOWS_DIR` でディレクトリを指定できる(YAML を足して再起動するだけで新しいマッピングを足せる)。`integration-flow validate` で起動の前に確かめられる。写せない値(変換に合わない形・対応表にない値)は `FlowMappingError`(NonRetryable。取り込みの側で DLQ)で、メッセージには項目の場所だけを入れる。
- 2026-10-21: P10 ⑤ で integration-flow の接続を実装した。§3 の新旧の判定の記録は表 `flow_version`(Flow とキーごとの版)で、Webhook の経路と定期の同期の経路が同じ部品(`RecordPublisher`)を通して使う(記録を進める 1 文の SQL の条件で、古い版で上書きしない)。§4 の冪等消費の記録と、新旧の判定の記録・Outbox の行は 1 つのトランザクションで書き、失敗すればすべて取り消す。取り込みのトピックの購読は、Flow の event の trigger のトピックから決める(Flow の YAML で新しい取り込みのトピックを足せる)。serve は起動時に、Flow の定義の検証に加えて、届け先(Canonical の型とトピックの組)を出せることと、poll の trigger の Connector があることを確かめる(出せる組・Connector はコードの側に足す)。写せない・Canonical の検証を通らない Webhook は DLQ に隔離する。定期の同期は、写せないレコードを数えて先に進むが、Watermark は進めない(次の回にもう一度読む。新旧の記録があるので出し直さない)。メトリクスは `eia.flow.records{flow,path,outcome}` と `eia.flow.sync.runs{flow,outcome}`。
- 2026-10-22: P10 ⑦ で、イメージの Flow に足して読む追加のディレクトリ(`INTEGRATION_FLOW_EXTRA_FLOWS_DIR`。既定は空のディレクトリ)を入れた。E2E(`make up ... E2E=1`)だけが `tests/e2e/flows` の Flow(`crm-customer-reactivated`。CRM の新しい種類の Webhook `customer.reactivated` を写す)を足し、コードを変えずに Flow の定義だけで新しいマッピングを追加できることを確かめる(ROADMAP P10 の DoD)。**本番の Flow は §1 のとおり contracts/flows に置き、契約と同じくレビューする**(追加のディレクトリは試験用。contract-check の対象外)。同じ ID の Flow が重なれば起動しない。新旧の判定の記録は Flow ごと。
