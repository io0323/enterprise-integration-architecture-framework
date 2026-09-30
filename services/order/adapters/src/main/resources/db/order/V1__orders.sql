-- 注文と明細(order-service。docs/architecture/order-state-machine.md)。
-- DB の所有者のロールで適用する。${appRole}: アプリが接続するロール(所有者の権限は持たない)。付ける権限は次のとおり。
--   orders:      SELECT / INSERT / UPDATE(状態の遷移と楽観的ロックの版。DELETE はしない)
--   order_lines: SELECT / INSERT(明細は変えない)
-- 金額は最小通貨単位の bigint と通貨コードで持つ(ADR-0011)。すべての明細の通貨は注文の通貨と同じ(domain の規則)。

CREATE TABLE orders (
    id                 varchar(64)  PRIMARY KEY,
    customer_id        varchar(64)  NOT NULL,
    status             varchar(16)  NOT NULL CHECK (status IN ('PLACED', 'CONFIRMED', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    ordered_at         timestamptz  NOT NULL,
    currency           char(3)      NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    total_amount_minor bigint       NOT NULL CHECK (total_amount_minor >= 0),
    ship_country_code  char(2)      NOT NULL,
    ship_postal_code   varchar(16)  NOT NULL,
    ship_region        varchar(128),
    ship_city          varchar(128) NOT NULL,
    ship_line1         varchar(256) NOT NULL,
    ship_line2         varchar(256),
    -- 楽観的ロックの版。UPDATE ... WHERE id = ? AND version = ? で比べ、1 増やす
    version            bigint       NOT NULL CHECK (version >= 0),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE order_lines (
    order_id          varchar(64) NOT NULL REFERENCES orders (id),
    line_number       integer     NOT NULL CHECK (line_number >= 1),
    product_id        varchar(64) NOT NULL,
    sku               varchar(64) NOT NULL,
    quantity          bigint      NOT NULL CHECK (quantity >= 1),
    unit_price_minor  bigint      NOT NULL CHECK (unit_price_minor >= 0),
    line_amount_minor bigint      NOT NULL CHECK (line_amount_minor >= 0),
    PRIMARY KEY (order_id, line_number)
);

REVOKE ALL ON orders, order_lines FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON orders TO "${appRole}";
GRANT SELECT, INSERT ON order_lines TO "${appRole}";
