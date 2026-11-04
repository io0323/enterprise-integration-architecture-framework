# ADR-0044: 障害注入の経路(Toxiproxy)
- Status: Accepted
- Date: 2026-11-04
- Framework 参照章: 13, 18
- 関連: ADR-0016(ローカル基盤)、ADR-0021(Resilience)、ADR-0043(BFF の部分応答)
- 番号: P14 の計画で決めた番号(0044 障害注入 / 0045 SLO / 0046 イベントの再発行 / 0047 secure profile)

## Context
ROADMAP P14 は、遅延・切断・Broker 停止の障害注入のシナリオを `tests/e2e` に集約することを求めている。これまでの E2E の障害は、コンテナの停止だけで確かめていた(`SagaE2E`・`FileExchangeE2E`・`BffE2E` など)。コンテナの停止では、遅延と、片方の経路だけの切断を作れない。

Toxiproxy は `chaos` profile で動いているが(P04b)、サービスの通信は通っていなかった。

## Decision
### 1. 接続先を切り替えるのは E2E のときだけ
- `make up ... E2E=1` のときだけ、`tests/e2e/e2e.env` でサービスの接続先を Toxiproxy に切り替える。通常の起動では Toxiproxy を通らないので、障害点が増えない。
- `E2E=1` は `chaos` profile を自動で足す。切り替えた接続先が起動しないことを防ぐため。
- サービスの `depends_on` の Toxiproxy は `required: false` にする。`chaos` がないときは待たない。

### 2. 通す経路
| proxy | 経路 | シナリオ |
|---|---|---|
| `postgres` | order-service → PostgreSQL | DB の遅延・切断 |
| `gateway-from-bff` | bff-graphql → Gateway | 下流の遅延・切断と部分応答 |
| `kafka-internal` | inventory-service → Kafka | Broker の切断・遅延(P14 ③) |

- **マイグレーションと Debezium は直接つなぐ**(接続先を切り替えるのは `serve` のサービスだけ)。障害を注入している間も、ほかの経路の結果を確かめられるようにする。
- 経路はシナリオに必要な分だけ足す。
- **起動し直すときも `e2e.env` を渡す。** 渡さないと、compose がサービスを作り直して接続先が Toxiproxy から外れ、障害のシナリオが何も確かめずに成功する。
- **gRPC は通さない。** クライアントはサーバ証明書の名前を接続先の名前で確かめるので、Toxiproxy を挟むには接続先の名前を上書きする設定が要る。本番にない設定を足さないよう、在庫と出荷の障害はコンテナの停止で確かめる(`BffE2E`)。

### 3. テストの部品
`Toxiproxy`(`tests/e2e`)は、遅延と切断を `whileDelayed`・`whileCut` で区切り、失敗しても必ず元に戻す(`/reset`)。

`FaultInjectionE2E` は、**通信が実際に Toxiproxy を通っていることを確かめる。** 通っていなければ、障害のシナリオは何も確かめていないのに成功してしまう。

## Alternatives Considered
- **常に Toxiproxy を通す**: 設定は単純になるが、通常の起動でも障害点が増え、Toxiproxy の停止で全サービスが止まる。不採用。
- **E2E のために compose のファイルを分ける**: 差分が環境変数の値だけなので、`e2e.env` で足りる。不採用。

## Consequences(トレードオフ)
- E2E の基盤は通常の起動と接続の経路が違う。遅延は無視できる程度だが、Toxiproxy の障害は E2E の失敗になる。
- `make verify` は Toxiproxy を通らない経路を確かめ、`make e2e` は通る経路を確かめる。

## 改訂履歴
