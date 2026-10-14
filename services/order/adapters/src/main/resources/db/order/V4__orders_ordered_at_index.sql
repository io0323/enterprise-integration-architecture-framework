-- 日次の集計(GET /v1/order-summaries/daily/{date}。ADR-0031 §3)は、受け付けの時刻の範囲で注文を読む。
CREATE INDEX orders_ordered_at ON orders (ordered_at);
