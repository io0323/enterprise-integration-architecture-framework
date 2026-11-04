# SLO のバーンレートのアラート

設計は ADR-0045。目標はカタログ(`contracts/catalog`)の値で、誤差の予算は 30 日あたり。

| SLO(ラベル `slo`) | 連携 | 目標 |
|---|---|---|
| `order-api-availability` | INT-SALES-001 | 可用性 99.95% |
| `order-api-latency` | INT-SALES-001 | p99 500ms |
| `storefront-availability` | INT-STOREFRONT-001 | 可用性 99.9% |
| `storefront-latency` | INT-STOREFRONT-001 | p99 1s |
| `order-events-publish` | INT-SALES-002 | 可用性 99.99%(接続していて、遅れが 5 秒以内) |

| アラート | 内容 | 重大度 |
|---|---|---|
| `SloBurnRateFast` | 予算の 14.4 倍の速さで使っている(1 時間と 5 分の窓) | critical |
| `SloBurnRateSlow` | 予算の 6 倍の速さで使っている(6 時間と 30 分の窓) | warning |
| `CallerDeadlineHigh` | 依存先の呼び出しの 1% 超が、呼び出し元の締め切りで打ち切られている | warning |

## 予算を速く使っているとき
利用者に影響が出ている。このままだと 2 日で 30 日分の予算を使い切る。

1. ラベル `slo` で連携を確かめる。
2. 可用性: Grafana の **Order API — RED** か **Storefront — BFF** で状態コードを見る。ゲートウェイ自身の 502 / 504 か、サービスの 5xx かで切り分ける。
3. 遅延: 同じダッシュボードの p95 / p99 を見て、`CallerDeadlineHigh` と下流のタイムアウトを確かめる。
4. `order-events-publish`: `docs/runbooks/cdc-outbox-lag.md` を見る。

## 予算をゆっくり使っているとき
すぐには利用者に大きな影響はないが、続くと 5 日で予算を使い切る。営業時間内に原因を調べる。手順は上と同じ。

## 呼び出し元の締め切りで打ち切られているとき
リクエストの予算の中で依存先が応答できていない。Circuit Breaker には数えないので(ADR-0021 §12)、依存先の障害のアラートは出ないことがある。

1. ラベルの `job` と `eia_dependency_name` で経路を確かめる。
2. Grafana の RED のダッシュボードの「呼び出し元の締め切りによる打ち切り」で、始まった時刻を確かめる。
3. 依存先が遅いなら、依存先を調べる。依存先が正常なら、予算の配分を見直す(BFF は ADR-0043 §6、order は ADR-0024 §3)。

閾値の 1% は測定の根拠がない初期値(ADR-0045 §4)。誤検知が多いときは、計測の結果で見直す。
