# bff-graphql(画面のための集約)の運用

設計は ADR-0043。ダッシュボードは Grafana の **Storefront — BFF**。

| アラート | 内容 | 重大度 |
|---|---|---|
| `BffOrderQuotaHigh` | Gateway の BFF の枠の使用率が 80% を超えた状態が 5 分続いた | warning |
| `BffPartialResponses` | 下流を読めず、部分応答を返す状態が 15 分続いた | warning |
| `GrpcClientCertificateFailure` | 在庫・出荷の gRPC の証明書のエラー(docs/runbooks/dev-certificates.md) | critical |

## BFF の枠の使用率が高いとき
枠を超えると、Gateway が 429 を返し、**全クライアントの**注文の項目が読めなくなる。

1. ダッシュボードの「Gateway の BFF の枠の使用率」と「リクエスト(クライアントの呼び出し)」を見る。
2. 特定のクライアントの増加なら、そのクライアントに確認する。クライアントの枠(既定 60 件 / 60 秒)の中でも、合計は BFF の枠を超えることがある。
3. 利用者の増加なら、枠を計算し直す。

   `BFF の枠 ≒ 画面のクライアントの数 × 画面のクライアントの枠`(ADR-0043 §9)

4. 枠は Gateway の環境変数で変える。Gateway を再起動すると反映される。アラートの分母は応答のヘッダから読むので、変更は要らない。

   ```bash
   EIAF_RATE_LIMIT_CLIENTS=eiaf-bff-graphql=1200 make up PROFILE="order saga bff"
   ```

`EIAF_RATE_LIMIT_DEFAULT`(一覧にないクライアントの枠)と `EIAF_RATE_LIMIT_CLIENTS` の形式が誤っていると、apisix は起動しない。

## 部分応答が続くとき
ラベル `service` のサービス(`orders`・`inventory`・`shipping`)を読めていない。読める項目は返している。

1. ダッシュボードの「下流の呼び出し」と、そのサービスのヘルスを見る。
2. `orders` なら、Gateway の 429(上の枠)も確かめる。
3. Circuit Breaker が開いていれば、下流が回復すると自動で戻る。

## クライアントに 429 を返しているとき
- **Gateway の枠**: 応答に `X-RateLimit-Limit` がある。クライアントごとに 60 秒あたりの件数で数える。
- **複雑度の枠**: 応答に `X-RateLimit-Limit` がなく、ダッシュボードの「拒否したクエリ」の `complexityRate` が増える。`BFF_COMPLEXITY_BUCKET_CAPACITY` と `BFF_COMPLEXITY_REFILL_PER_SECOND` で変える。バケットはプロセスの中にあるので、再起動で満杯に戻る。
