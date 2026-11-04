# P14: 障害シナリオの期待挙動・観測方法・結果

ROADMAP P14 の障害注入の記録。経路は ADR-0044、実行は `make e2e`(CI の `e2e` ジョブ)。

**結果の出どころ**: CI の Run 41812221489(2026-11-04。コミット `767418e`)。所要時間はこの 1 回の値で、性能の保証ではない。

## 結論
- Toxiproxy で注入した 7 シナリオは、すべて期待どおりに動く。
- **最初の実行では 7 つのうち 2 つが製品の問題で失敗した**(下の「見つかった問題」)。どちらも修正した。
- Saga・ファイル授受などのコンテナの停止のシナリオは、これまでの E2E で確かめている。

## Toxiproxy で注入するシナリオ(`ResilienceE2E`)
| # | 障害 | 注入 | 期待挙動 | 観測方法 | 結果 |
|---|---|---|---|---|---|
| R1 | DB の遅延 | order-service → PostgreSQL に 12 秒の遅延 | 予算(10 秒)で 503 `deadline-exceeded` + `Retry-After`。Gateway の 504 にしない。戻すと読める | HTTP の状態コード・Problem の `type`・所要時間 | 成功(応答まで約 10 秒) |
| R2 | DB の切断 | order-service → PostgreSQL を切断 | 注文の作成は 503 で 500 にしない。戻した後に同じ `Idempotency-Key` で送り直すと、注文は 1 件だけ | HTTP の状態コード・`Idempotent-Replayed`・注文 ID | 成功 |
| R3 | 下流の遅延 | bff-graphql → Gateway に 3 秒の遅延 | 注文の配分(2 秒)で打ち切り、全体の予算(5 秒)の中で部分応答(HTTP 200 + `UPSTREAM_UNAVAILABLE`) | 応答の `errors`・所要時間 | 成功(約 2 秒) |
| R4 | 下流の切断の継続 | bff-graphql → Gateway を切断 | Circuit Breaker が開き、メトリクスで分かる。戻すと閉じて読める | `eia_resilience_circuit_breaker_state_ratio`・応答 | 成功 |
| R5 | Broker への切断 | inventory-service → Kafka を 8 秒切断 | 切れている間は Saga が進まない(PLACED)。戻すと欠けずに SHIPPED | 注文の状態 | 成功 |
| R6 | Broker への遅延 | inventory-service → Kafka に 1 秒の遅延 | 遅くなるだけで、欠けずに SHIPPED | 注文の状態 | 成功 |
| R7 | Broker の停止 | Kafka のコンテナを停止 | 注文は受け付ける(Outbox)。再開すると欠けずに SHIPPED | HTTP の状態コード・注文の状態 | 成功 |

## コンテナの停止で確かめるシナリオ(移さず参照する)
遅延と片方の経路の切断が要らないものは、これまでの E2E のまま。

| 障害 | 期待挙動 | テスト |
|---|---|---|
| 在庫のサービスの停止 | 引当の期限を過ぎると解放を送り、再開の後に CANCELLED(TIMED_OUT)。在庫は残らない | `SagaE2E` |
| Poison Message | パーティションごとに DLQ に隔離し、後ろの注文は止まらない。Replay で再処理できる | `SagaE2E` |
| 在庫・出荷のサービスの停止 | その項目だけ null + `UPSTREAM_UNAVAILABLE`。ほかの項目は返り、HTTP は 200 | `BffE2E` |
| バッチの途中の kill | 再実行しても重複・欠損がなく、order-service の集計と一致する | `BatchE2E` |
| 取引先の SFTP の停止 | 送信の失敗と Circuit Breaker の Open が Prometheus に届き、戻した後に次の回で送り終える | `FileExchangeE2E` |
| SaaS の 429 | `Retry-After` を待って再試行し、Rate Limit を戻すと同期が成功する | `SaasE2E` |

## 見つかった問題
| シナリオ | 問題 | 影響 | 修正 |
|---|---|---|---|
| R1・R2 | DB のネットワークが遅い・切れていると、予算が効かず Gateway の 504 になった。ADR-0024 は「遅くとも予算 + 余裕で止まる」としていたが、`statement_timeout` は DB に届かないと効かない | クライアントには結果が分からない応答(504)になる | DB の接続と通信に上限を置き、打ち切りのときに JDBC の完了を待たない(ADR-0024 §3) |
| R2 | DB に届かないとき、注文の作成が 500 になった | 一時的な障害が実装の誤りに見え、再試行してよいかが分からない | DB の接続の失敗を 503 + `Retry-After` にする(全サービス) |
| R2 の調査 | DB に届かない例外を、Kafka の処理が「想定しない例外」として扱い、続くと DLQ に隔離する | DB の障害が長いと、正常なメッセージが DLQ に入る | DLQ に隔離せず、未処理の位置から読み直す(全サービス) |

あわせて、テストの誤りも直した(Circuit Breaker のメトリクスの名前。`BffE2E`・`SagaE2E` が起動し直すときに `e2e.env` を渡していなかった)。

## 残る制限
- **R1 の修正の対象は order-service だけ。** inventory・shipping ほかの DB の接続に同じ上限を置くかは確かめていない。
- **遅延の間の POST は、Gateway の 504 になりうる。** 打ち切りの後の後始末(冪等の記録の取り消し)が DB を待つため。切断なら接続の上限(3 秒)で返る。R1 は GET で確かめている。
- **gRPC の経路は Toxiproxy を通らない**(ADR-0044 §2)。在庫・出荷の遅延は確かめていない。停止だけ。
- **1 回の実行の結果。** E2E は CI で毎回動くが、ここに書いた時間は 1 回の値。Tier ごとの目標との比較は `docs/reports/p14-nfr.md`(P14 ⑩)で行う。
