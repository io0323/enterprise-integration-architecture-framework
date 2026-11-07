# P14: Tier 別の NFR の計測

ROADMAP P14 の「Tier 別 NFR(Framework 18 章)の計測レポート」。計測の方法と判断は ADR-0048。

**ここの数値は「ローカルの参照実装での目安」で、本番の性能値ではない**(ADR-0017 の計測。`docs/reports/p04a-audit-throughput.md` と同じ扱い)。主な値は GitHub の標準のランナー(4 コア・15 GiB)の上で測った。手元の 1 台の値は付録に参考として残す。

## 要約
- 測った 17 の連携のうち 14 が、カタログの SLO を 2 回とも満たした(INT-SALES-001 はカタログの値で数えた)。満たさなかった 3 つ(INT-INVENTORY-002・INT-PAYMENT-002・INT-SHIPPING-002)は、どれも order-service が Saga の返事を受けて次のコマンドを出す段の待ち行列(既知の問題 1)。発行(Outbox → Kafka)はどれも p99 0.5 秒前後で満たしている。
- INT-SALES-001 は、カタログ(p99 500ms)を満たした。Framework の Tier 1(p99 300ms)は、Gateway の値で 1 回目に超えた(414ms)。カタログの値のまま変えない(ADR-0048 §7.1)。
- 複雑度の枠(大きさ 1000・毎秒 60)は設計どおりに効いた。値は変えない(§7.2)。
- `caller_deadline` のしきい値(1%)は変えない。遅延が大きいときの部分応答は、どのアラートにも映らない(§7.3。既知の問題 4)。
- 計測しなかった連携は 4 つ(B2B の 3 つと日次ファイル。時間枠の SLO)と、相手が模擬の 1 つ(INT-SALES-006)。

## 計測の環境(主な値)
| 項目 | 値 |
|---|---|
| 実行 | `nfr` の [run 42369263281](https://github.com/io0323/enterprise-integration-architecture-framework/actions/runs/42369263281)(2026-11-07 11:42〜12:18 UTC、`stages=a,b,c`、`runs=2`)。コミット `173c279` |
| ランナー | `ubuntu-latest`(Linux 7.0.0-1019-azure)、4 コア、メモリ 15 GiB、Docker 29.4.2(VM を挟まない) |
| CPU の機種(`lscpu`) | A: Intel Xeon Platinum 8573C / B: Intel Xeon Platinum 8370C / C: AMD EPYC 7763。ジョブごとに違う。同じジョブの 2 回は同じ機械なので、2 回の差は機種の違いではない |
| 起動する前の確認 | ほかのコンテナがなく、load average がコア数の半分(2)以下。準備の直後の負荷(1.3〜3.7)で 1〜2 分待ってから通った |
| 計測を始める前の load average | A 0.65〜0.96、B 0.48〜1.71、C 0.55〜0.59(上限 `max_load` は当時の既定の 6。今の既定は 2。ADR-0048 §7.4) |
| 発行の経路 | 12 回すべて、計測の前後でコネクタが RUNNING、計測の間の Kafka の切り離しは 0 回(`pipelineCheck.usable: true`) |
| コンテナのメモリ | 合計 A 4.2 GB、B 3.9 GB、C 4.4 GB(`make stats`) |
| Gateway の枠 | 計測の間だけ `EIAF_RATE_LIMIT_CLIENTS=eiaf-e2e=1000000,eiaf-e2e-b=1000000,eiaf-bff-graphql=1000000`。**「Gateway の枠で止められない状態での、サービス側の性能」**。Webhook のルートの枠(remote_addr ごとに 60 秒 120 件)は変えていない |
| 構成 | 通常の構成(Grafana・Prometheus・Loki・Tempo なども動かしたまま)。転送路は平文で、`secure` profile ではない(TLS と認証の負荷は入っていない) |

結果の JSON と環境の記録は `docs/reports/p14-nfr/runner/`(成果物 `nfr-a`・`nfr-b`・`nfr-c` の写し。成果物は 90 日で消える)。

## シナリオと負荷
| 段 | シナリオ | 負荷 | 測る区間 |
|---|---|---|---|
| A | `order-api` | 慣らし 200 組の後、注文の POST と GET を 3,000 組、並列 16 で送り続ける(上限に近い負荷) | クライアントと Gateway の遅延、Outbox → Kafka、Saga の各段(相関 ID で注文とイベントを対応づける) |
| A | `storefront` | 画面の詳細(複雑度 7)を毎秒 8 件 × 120 秒、20 件の一覧(複雑度 140)を毎秒 0.4 件 × 120 秒、枠を超える送り方(複雑度 7 を毎秒 15 件 × 60 秒) | クライアントと Gateway の遅延、gRPC、部分応答、429 |
| B | `webhook` | 署名した Webhook を 550ms ごとに 200 件 | 受け付け、received → customer.changed、発行 |
| B | `iot` | MQTT(QoS 1)を毎秒 10 件 × 600 件 | 機器の送信 → Kafka で読める |
| B | `cdc` | 旧システムで受注 50 件の作成と 50 件の更新を 4 回 | 旧システムのコミット → Kafka |
| C | `caller-deadline` | 画面の詳細を毎秒 6 件 × 90 秒を、bff-graphql → Gateway の遅延 0・600±400・1000±600・1300±600ms で | `caller_deadline` の割合、部分応答、遅延 |

## 連携ごとの結果
表は `python3 scripts/reports/nfr-table.py --write` がカタログ(`contracts/catalog`)と計測の結果(`docs/reports/p14-nfr-integrations.json`)から作る。
カタログのすべての連携が載っていることは `./gradlew build`(`NfrReportSpec`)が確かめる(数値は確かめない)。
「結果」は 2 回の値を「1 回目 / 2 回目」で書く。判定はカタログの SLO の遅延に対して付けた。可用性は数分の計測では判定できないので、5xx の件数だけを書く。

<!-- nfr-table:begin -->
| ID | 名前 | 方式 | Tier | カタログの SLO(可用性 / p99) | Framework の Tier の目標 | 計測 | 結果 | 判定 | 計測しない理由・備考 |
|---|---|---|---|---|---|---|---|---|---|
| INT-B2B-001 | Partner Purchase Orders (EDIFACT ORDERS) | edi | 2 | 99.9% / 15m | 99.9% / 時間枠(7・9 章) | 計測しない | — | — | SLO は受信から処理までの時間枠(15 分)で、流量の計測では意味のある値にならない。動作は B2bGatewayE2E で確かめている |
| INT-B2B-002 | Syntax Acknowledgements (EDIFACT CONTRL) | edi | 2 | 99.9% / 15m | 99.9% / 時間枠(7・9 章) | 計測しない | — | — | INT-B2B-001 と同じ(時間枠 15 分) |
| INT-B2B-003 | Business Acknowledgements (EDIFACT APERAK) | edi | 2 | 99.9% / 1h | 99.9% / 時間枠(7・9 章) | 計測しない | — | — | INT-B2B-001 と同じ(時間枠 1 時間) |
| INT-INTEGRATION-001 | CRM Webhook Ingest | event | 2 | 99.9% / 5s | 99.9% / Event < 30s | 計測した | received → customer.changed を Outbox に書く p99 947 / 926ms(各 200 件、欠けなし) | 満たした | B(webhook) |
| INT-INVENTORY-001 | Inventory Stock Commands | event | 1 | 99.9% / 5s | 99.99% / Event E2E < 5s | 一部 | cmd-reserve → reserved p99 1,230 / 1,197ms(各 3,000 件) | 満たした | 引当のコマンドだけ。解放(cmd-release)は補償の経路で、正常の負荷では流れないので測っていない |
| INT-INVENTORY-002 | Inventory Stock Events | event | 1 | 99.9% / 5s | 99.99% / Event E2E < 5s | 計測した | 発行 p99 496 / 496ms。reserved → 次のコマンド(order-service が cmd-authorize を出す)p99 18.6 / 14.3s | 満たさない(消費側) | 3,000 件を 17〜24 秒で受けた直後の、order-service の Saga の待ち行列(既知の問題 1)。最大 19.2s で、段の期限(30s)には届いていない |
| INT-INVENTORY-003 | Stock Query | grpc | 2 | 99.9% / 200ms | 99.9% / API p99 < 1s | 計測した | 提供側の p99 9.5 / 5.0ms | 満たした | A(storefront) |
| INT-IOT-001 | Device Telemetry (MQTT) | mqtt | 2 | 99.9% / 5s | 99.9% / Event < 30s | 計測した | 機器の送信 → Kafka で読める p99 20 / 12ms(各 600 件、10 件/秒、QoS 1、失敗 0) | 満たした | INT-IOT-002 と合わせた区間(ホストの時計) |
| INT-IOT-002 | IoT Telemetry Events | event | 2 | 99.9% / 5s | 99.9% / Event < 30s | 計測した | INT-IOT-001 と同じ区間で計測 | 満たした | Bridge から Kafka への発行は、機器の送信からの区間に含めて測った |
| INT-PAYMENT-001 | Payment Commands | event | 1 | 99.9% / 5s | 99.99% / Event E2E < 5s | 一部 | cmd-authorize → authorized p99 1,912 / 1,474ms | 満たした | 承認のコマンドだけ。取消(cmd-void)は補償の経路で測っていない |
| INT-PAYMENT-002 | Payment Events | event | 1 | 99.9% / 5s | 99.99% / Event E2E < 5s | 計測した | 発行 p99 492 / 484ms。authorized → 次のコマンド(cmd-arrange)p99 17.7 / 11.9s | 満たさない(消費側) | INT-INVENTORY-002 と同じく order-service の Saga の待ち行列(既知の問題 1) |
| INT-SALES-001 | Order API | rest | 1 | 99.95% / 500ms | 99.99% / API p99 < 300ms | 計測した | クライアントで POST p99 239 / 177ms、GET p99 67 / 45ms(並列 16、247 / 360 件/秒)。Gateway p99 414 / 184ms。5xx 0 件 | カタログは満たした。Framework の 300ms は Gateway の 1 回目で超えた | 並列 16 で送り続けた、ランナーの上限に近い負荷。SLO の扱いは ADR-0048 §7.1 |
| INT-SALES-002 | Order Events | event | 1 | 99.99% / 5s | 99.99% / Event E2E < 5s | 計測した | Outbox → Kafka p99: created 505 / 500ms、status-changed 489 / 483ms | 満たした | 発行までの区間。消費側の遅れは Saga の各段(INVENTORY / PAYMENT / SHIPPING)の行に書いた |
| INT-SALES-003 | Legacy Order Changes | cdc | 2 | 99.9% / 60s | 99.9% / Event < 30s | 計測した | 旧システムのコミット → Kafka p99 1,141 / 1,239ms(変更 400 / 532 件) | 満たした | 50 件ずつまとめてコミットする変更。2 回目の件数には、1 回目の受注の変更が含まれる(計測の道具が 5 分前から読むため) |
| INT-SALES-004 | Daily Sales File | file | 2 | 99.9% / 8h | 99.9% / 時間枠(7・9 章) | 計測しない | — | — | SLO はバッチの時間枠(8 時間)。再実行の安全性と Checkpoint は統合テストと BatchE2E で確かめている |
| INT-SALES-005 | CRM Webhook | webhook | 2 | 99.9% / 1s | 99.9% / API p99 < 1s | 計測した | 受け付け(Gateway 経由)p99 19 / 14ms(各 200 件、すべて 204) | 満たした | Gateway の webhook のルートの枠(remote_addr ごとに 60 秒 120 件)に収まる間隔(550ms)で送った |
| INT-SALES-006 | CRM Customer API | rest | 3 | 99.5% / 2s | 99.5% / バッチの SLA | 計測しない | — | — | 相手は手元の模擬(saas-mock)で、外部の SaaS の性能を表さない。定期の同期で読む API で、利用者の待ち時間にもならない |
| INT-SALES-007 | Customer Events | event | 2 | 99.9% / 30s | 99.9% / Event < 30s | 計測した | Outbox → Kafka p99 494 / 493ms(各 200 件) | 満たした | B(webhook) |
| INT-SHIPPING-001 | Shipping Commands | event | 1 | 99.9% / 5s | 99.99% / Event E2E < 5s | 一部 | cmd-arrange → shipped p99 1,645 / 1,212ms | 満たした | 手配のコマンドだけ。取消(cmd-cancel)は補償の経路で測っていない |
| INT-SHIPPING-002 | Shipping Events | event | 1 | 99.9% / 5s | 99.99% / Event E2E < 5s | 計測した | 発行 p99 490 / 477ms。shipped → 注文の状態 SHIPPED p99 13.6 / 7.0s | 満たさない(消費側) | INT-INVENTORY-002 と同じく order-service の Saga の待ち行列(既知の問題 1) |
| INT-SHIPPING-003 | Shipment Query | grpc | 2 | 99.9% / 200ms | 99.9% / API p99 < 1s | 計測した | 提供側の p99 9.3 / 5.0ms | 満たした | A(storefront) |
| INT-STOREFRONT-001 | Storefront GraphQL | graphql | 2 | 99.9% / 1s | 99.9% / API p99 < 1s | 計測した | SINGLE p99 44 / 22ms(1s 超 0%)、LIST20 p99 58 / 31ms。部分応答 0 件、5xx 0 件 | 満たした | 8 件/秒(複雑度 56/秒)。枠を超える送り方の結果は ADR-0048 §7.2 |
<!-- nfr-table:end -->

## 2 回の差
- 差は最大で約 2 倍(例: 注文の POST の p99 239 / 177ms、画面の詳細の p99 44 / 22ms、IoT の p99 20 / 12ms、Saga の全体の p99 35.3 / 25.0 秒)。どちらの回も判定は変わらない。
- 各ジョブの 2 回は同じ機械で、2 回目のほうが速い傾向がある(1 回目が JIT や接続を温める分が残っている可能性。慣らしの送信はしている)。
- 手元の 1 台では、起動の直後の回が 4〜50 倍遅いことがあった(付録)。ランナーでは、そこまでの差は出ていない。

## 判断(ADR-0048 §7)
- **INT-SALES-001**: カタログの 99.95% / p99 500ms のまま。Framework の Tier 1(99.99% / 300ms)は本番の目標として残し、採用する側が自分の基盤で測り直す(§7.1)。ADR-0045 §5 の持ち越しを閉じる。
- **複雑度の枠**: 大きさ 1000・毎秒 60 のまま。枠を超える送り方の 900 件のうち、2 回とも 636 件が通り 264 件が 429(計算の上限は約 657 件)。枠に収まる送り方では 429 は 0 件(§7.2)。
- **`caller_deadline`**: 1% のまま。増えるのは、試行の上限(1.5 秒)と注文の取り分(2 秒)の間にかかる帯だけ(1.04〜1.39%)で、それより遅いと試行の時間切れと Circuit Breaker が先に起きて 0.18% に戻る(§7.3)。

## 既知の問題
1. **Saga の消費側の待ち行列**(INT-INVENTORY-002・INT-PAYMENT-002・INT-SHIPPING-002)。3,000 件を 17〜24 秒で受けた直後、order-service が返事を受けて次のコマンドを出す段が p99 12〜19 秒になった。最大 19.2 秒で、段の期限(30 秒)には届いていないが、手元では 29.9 秒まで延びた。参加者の側(在庫・決済・出荷がコマンドを受けて返事を出す段)は p99 1.2〜1.9 秒で、order-service の Saga が流れの細いところになっている。
2. **起動の直後の遅さ**(#199 と同じ種類)。gRPC の提供側は最初の呼び出しが遅く、bff-graphql の試行の時間切れで Circuit Breaker が開き、温まるまで部分応答が続く。計測では慣らしで避けている(ADR-0048 §4)。手元では温まる前の回の部分応答が 98% になった。
3. **Saga の段の期限と Kafka Connect の割り当て直しの待ち時間**(#215)。単一ノードの Kafka が切り離されると、コネクタが既定 5 分の間 UNASSIGNED になり、その間の注文が段の期限(30 秒)で取り消される(手元で 196 件)。送り直しは Outbox に積まれ、回復の直後にまとめて流れる。
4. **部分応答がアラートに映らない**(ADR-0048 §7.3)。C の遅延 1300±600ms では部分応答が 68〜94% になったが、部分応答は速い 200 なので、画面の可用性(5xx)にも遅延(1 秒超)にも数えられない。部分応答の割合を画面の SLI にすることを、別の Issue で検討する。
5. **出荷のサービスのメモリ**。A と C で、出荷のサービスが割り当て(256 MiB)の 97% を使っていた(計測の終わりの `make stats`)。
6. **Outbox → Kafka の p99 が 0.5 秒前後にそろう**。どの発行も p99 が約 490〜590ms だった。Debezium の既定の poll の間隔(500ms)で待つ分と見ている(確かめてはいない)。
7. **ローカルの Kafka のデータが volume に残らなかった**(#213。計測の後に直した)。計測の時点では、コンテナを作り直すとトピックと Kafka Connect のオフセットが消えていた。ランナーではジョブごとに新しく起動するので、計測の値には影響しない。

## ADR-0008 で範囲外・縮退とした項目
計測は通常の構成(ローカルの縮退のまま)で行った。次の項目の負荷は、数値に入っていない。

| 項目 | ADR-0008 の扱い | 数値への影響 |
|---|---|---|
| 保存データの暗号化(12.2) | 範囲外 | 暗号化の負荷は入っていない |
| Service Mesh(17.2) | 範囲外(mTLS は APISIX → サービスと gRPC に直接設定) | サイドカーの分の遅延は入っていない。APISIX → サービスと gRPC の mTLS は入っている |
| Event Mesh(4.1) | 範囲外(単一の Kafka クラスタ) | 複数の Broker・リージョンの間の遅延は入っていない。Kafka は単一ノード |
| 転送路の暗号化(9, 12.2) | ローカルは平文(`secure` profile で一部を暗号化) | Kafka・MQTT・S3・Keycloak・APISIX の TLS の負荷は入っていない |
| 基盤の管理 API の認証(12.3) | ローカルは未認証(`secure` profile で一部を認証) | 計測の経路には入らない |
| Secrets(12.2) | 環境変数と Docker secrets | 計測の経路には入らない |
| ワークフロー(7.2)・B2B Protocol(11.1)・Audit(14.1) | 軽量の DAG ランナー・SFTP のみ・ハッシュチェーン | 該当する連携(INT-SALES-004・INT-B2B-*)は計測しない。監査の追記の性能は ADR-0017 で測った |

## 付録: 手元の 1 台での計測(参考)
主な値をランナーで取る前に、手元の 1 台で測った値。**「ローカルの参照実装での目安」で、本番の性能値ではない**(ADR-0017 と同じ扱い)。結果の JSON は `docs/reports/p14-nfr/local/`。

### 環境
- Apple M3、8 コア、メモリ 8 GiB、macOS 26.3.1。Docker 29.8.0 の VM に CPU 8・メモリ約 7.75 GiB(8,319,770,624 バイト)。計測の後半には、ホストのスワップを 5.9 GB(7.2 GB 中)使っていた。
- **基盤を起動する前の確認(load average の上限はコア数の半分の 4)で、外からの負荷を除いた。** 最初はほかのプロジェクトのコンテナが動いていて、VM が過負荷になっていた。止めてから測った。
- **基盤を起動した後の上限は 10 にした。** 基盤(`order saga bff`)が何もしていないときの負荷が 7〜10 あるため。何もしていないときの load average の推移は次のとおり。

  | 時点 | load average(1 分) |
  |---|---|
  | 基盤を起動する前 | 3.31 |
  | 起動の約 5 分後 | 9.64 |
  | 起動の約 20 分後 | 6.7〜8.1(一時的に 14.5) |

- 構成は通常のまま(Grafana などは止めていない)。計測の間はブラウザを閉じ、IDE(Claude Code を動かしている)は開いたままにした。
- Gateway の枠は、ランナーと同じ値で上げた。

### 値(A と B)
| シナリオ | 回 | 主な値 |
|---|---|---|
| order-api | 3 回 | POST p99 1,207 / 661 / 540ms、GET p99 284 / 184 / 149ms、Gateway p99 1,575 / 548 / 496ms、Saga の全体 p99 52.8 / 44.9 / 41.5 秒(147〜219 件/秒)。1 回目だけ大きく遅い |
| storefront | 2 回(きれいに測れた回) | 画面の詳細 p99 372 / 745ms、20 件の一覧 p99 367 / 355ms、枠を超える送り方 637 / 637 件が通った |
| webhook | 2 回 | 受け付け p99 145 / 240ms、received → customer.changed p99 4,277 / 948ms |
| iot | 2 回 | p99 4,678 / 92ms |
| cdc | 2 回 | p99 13.5 / 10.8 秒 |

- どのシナリオも、起動の直後の 1 回目が遅い傾向があった(webhook と iot は 4〜50 倍)。
- storefront は、ほかに 3 回測ったが、温まる前の回(部分応答 98%)と、計測の道具の不具合で 20 件の一覧に 401 が出た 2 回は除いた。

### 測れなかったこと
- **C(`caller_deadline`)は測れなかった。** `E2E=1` の構成では、在庫と出荷の gRPC が遅延を入れる前から p99 0.5〜4.7 秒になり、計測の途中にも Kafka が 3 回切り離された。
- 単一ノードの Kafka は、高い負荷の中で heartbeat を送れずに何度も切り離された。手元で `./gradlew build` を動かすだけでも切り離された。そのため、主な値をランナーで取ることにした(ADR-0048 §1)。
