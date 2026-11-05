# ADR-0045: SLO のバーンレートのアラート
- Status: Accepted
- Date: 2026-11-04
- Framework 参照章: 14, 16, 18
- 関連: ADR-0021(Resilience)、ADR-0023(Gateway)、ADR-0043(BFF)
- 番号: P14 の計画で決めた番号(0044 障害注入 / 0045 SLO / 0046 イベントの再発行 / 0047 secure profile)

## Context
ROADMAP P14 は SLO のバーンレートのアラートを求めている。カタログには連携ごとに SLO があるが、これまでのアラートは個別の障害(コネクタの停止など)を見るだけで、目標に対してどれだけ悪いかを見ていなかった。P05 から、`caller_deadline` のアラートのしきい値も持ち越していた(ADR-0021 の改訂履歴)。

## Decision
### 1. 範囲
**Tier 1 の同期 API(INT-SALES-001)、INT-STOREFRONT-001、Event の代表 1 本(INT-SALES-002)から始める。** カタログの全連携には設定しない。ローカルでは流量が足りず、ほとんどのアラートを実際に確かめられないため。設定しなかった連携は、計測のレポート(`docs/reports/p14-nfr.md`)に理由とともに載せる。

### 2. SLI
| SLO | SLI | 誤差の予算 |
|---|---|---|
| `order-api-availability` | Gateway の 5xx の割合(route `sales-order-api`) | 0.05% |
| `order-api-latency` | Gateway で 500ms を超えた割合(`le="500"`) | 1%(p99) |
| `storefront-availability` | Gateway の 5xx の割合(route `storefront-graphql`) | 0.1% |
| `storefront-latency` | Gateway で 1s を超えた割合(`le="1000"`) | 1%(p99) |
| `order-events-publish` | Debezium が接続していて、最後のイベントの遅れが 5 秒以内である時間の割合 | 0.01% |

- **Gateway で測る。** クライアントが受け取った結果に近く、Gateway 自身の 502 / 504 も入る。
- **部分応答は誤りに数えない。** HTTP 200 で読める項目は返していて、`BffPartialResponses` で別に見る(ADR-0043 §6)。
- **遅延の割合は Gateway のヒストグラムのバケットで数える。** 目標の値(500ms・1s)がバケットの境界にある。
- **イベントの SLI は時間の割合で、イベントの数の割合ではない。** Debezium の遅れは最後のイベントの値で、流量がない間は変わらない。

### 3. バーンレートの窓(Google SRE Workbook の多重窓)
| アラート | バーンレート | 窓 | `for` | 重大度 | 意味 |
|---|---|---|---|---|---|
| `SloBurnRateFast` | 14.4 倍 | 1 時間 かつ 5 分 | 2 分 | critical | 30 日の予算の 2% を 1 時間で使う |
| `SloBurnRateSlow` | 6 倍 | 6 時間 かつ 30 分 | 15 分 | warning | 30 日の予算の 5% を 6 時間で使う |

- 長い窓で続いていることを、短い窓で今も起きていることを確かめる。回復すると短い窓から先に消える。
- **1 日・3 日の窓は置かない。** ローカルでは基盤を数日動かし続けないので、確かめられない。
- **ルールのテストは promtool で CI に入れる**(`slo.test.yml`)。時間を測るテストではないので、ROADMAP の「時間を測るテストは CI に入れない」には当たらない。
- **promtool の合成のデータだけでは足りない。** 実際のメトリクスに合っていることを `make verify` で確かめる。最初の式は promtool を通ったが、本物では次の 3 つの理由で動かなかった。
  - 5xx が 1 件もないと分子の系列がなく、割合が 0 ではなく「データなし」になった。
  - Prometheus 3 は `le` を `500.0` の形に正規化するので、`le="500"` に一致しなかった。
  - Debezium の `millisecondsbehindsource` には累積の統計(`MaxValue`・`P99` など)の系列も混ざる。これを拾うと、正常でも割合が 100% に張り付き、critical が出続ける。

### 4. `caller_deadline` のアラート
依存先の呼び出しのうち、呼び出し元の締め切りで打ち切られた試行の割合が **1% を超えた状態が 10 分続いたら warning**(`CallerDeadlineHigh`)。

- **分母のメトリクスがなかったので足した**(`eia.resilience.attempts`。`ResilienceListener.onAttempt`)。
- **1% は測定の根拠がない初期値。** P14 の計測のあとで見直す。

### 5. カタログと Framework の差
INT-SALES-001 のカタログは 99.95% / p99 500ms、Framework 18 章の Tier 1 は 99.99% / p99 300ms。**ここではカタログの値を使い、書き換えない。** 計測のあとで、どちらを直すかを決める(P14 ⑩)。

### 6. ダッシュボード(P14 ⑤)
全体の一覧 **EIAF — SLO & Resilience** を足す。既存のダッシュボードは複製せず、詳細はそちらで見る。

| ROADMAP の項目 | ダッシュボード |
|---|---|
| RED | Order API — RED、Storefront — BFF |
| Consumer Lag | 一覧、Order — Saga、CDC — Legacy、SaaS |
| DLQ | 一覧、Order — Saga、CDC — Legacy |
| Batch SLA | 一覧、Batch |
| replication slot の遅延 | 一覧、CDC — Outbox |
| SLO のバーンレート | 一覧 |

## Alternatives Considered
- **カタログの全連携に設定する**: ローカルで確かめられないアラートが増える。不採用。
- **サービスのメトリクスで測る**: Gateway 自身の 502 / 504 が入らず、クライアントの結果より良く見える。不採用。
- **アラートをカタログから生成する**: 全連携に広げるなら必要だが、5 つの SLO には過剰。ルールの目標とカタログの一致は `AlertGovernanceSpec` で検査する。

## Consequences(トレードオフ)
- 目標の値がカタログとルールの 2 か所にある。一致は `AlertGovernanceSpec` で検査する(P14 ⑥)。
- ローカルの流量では、実際に発火する様子を確かめられない。ルールの正しさは promtool の合成のデータで確かめる。
- 「流量がないと発火しない」ので、完全に止まったことはこのアラートでは分からない。

## 改訂履歴
- 2026-11-05: P14 ⑥ で、全アラートの Runbook と、SLO のルールとカタログの一致を `AlertGovernanceSpec`(`./gradlew build`)で検査するようにした。
