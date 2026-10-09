-- 在庫の初期データ(模擬。ADR-0029 §7)。E2E の在庫不足のシナリオのために、在庫のない SKU も置く。
-- 本番の在庫は在庫管理のシステムから取り込む(P07 の対象外)。
INSERT INTO stock (sku, on_hand) VALUES
    ('SKU-1', 1000000),
    ('SKU-2', 1000000),
    ('SKU-3', 1000000),
    ('SKU-LIMITED', 10),
    ('SKU-SOLDOUT', 0);
