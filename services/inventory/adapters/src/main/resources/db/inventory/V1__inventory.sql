-- 在庫と引当の記録(inventory-service。注文 Saga の参加者。ADR-0029 §5・§7)。
-- DB の所有者のロールで適用する。${appRole}: アプリが接続するロール(所有者の権限は持たない)。付ける権限は次のとおり。
--   stock:            SELECT / UPDATE(reserved と updated_at だけ。在庫の数そのものは変えない)
--   reservation:      SELECT / INSERT / UPDATE(status と settled_at だけ)/ DELETE(保持期間を過ぎた終わった記録の削除)
--   reservation_line: SELECT / INSERT(DELETE は reservation の削除の CASCADE で行う)
-- ${cdcRole}: Debezium。この DB への CONNECT を付ける(Outbox の表の SELECT は OutboxSchema が付ける)。
-- 時刻は DB の時計(clock_timestamp())で書く。保持期間の判定も DB の時計で行う(ADR-0029 §5)。

-- 引当できる数は on_hand - reserved。在庫は負にならない(DB の制約でも守る。アプリは SKU の順にロックしてから判定する)
CREATE TABLE stock (
    sku        varchar(64) PRIMARY KEY,
    on_hand    bigint      NOT NULL CHECK (on_hand >= 0),
    reserved   bigint      NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT stock_reserved_within_on_hand CHECK (reserved >= 0 AND reserved <= on_hand)
);

-- Saga ID ごとに 1 行(業務キーの冪等。ADR-0029 §5)。RELEASED_BEFORE_RESERVATION は「取消済み」の印
CREATE TABLE reservation (
    saga_id          varchar(64) PRIMARY KEY,
    order_id         varchar(64) NOT NULL,
    status           varchar(32) NOT NULL CHECK (status IN ('RESERVED', 'REJECTED', 'RELEASED', 'RELEASED_BEFORE_RESERVATION')),
    rejection_reason varchar(32) CHECK (rejection_reason IN ('INSUFFICIENT_STOCK', 'UNKNOWN_SKU', 'ALREADY_RELEASED')),
    created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
    -- 終わった時刻(RESERVED 以外)。保持期間の起点
    settled_at       timestamptz,
    CONSTRAINT reservation_settled CHECK ((status = 'RESERVED') = (settled_at IS NULL)),
    CONSTRAINT reservation_rejection CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL))
);

CREATE INDEX reservation_settled_at ON reservation (settled_at) WHERE settled_at IS NOT NULL;

CREATE TABLE reservation_line (
    saga_id     varchar(64) NOT NULL REFERENCES reservation (saga_id) ON DELETE CASCADE,
    line_number integer     NOT NULL CHECK (line_number >= 1),
    sku         varchar(64) NOT NULL,
    quantity    bigint      NOT NULL CHECK (quantity >= 1),
    PRIMARY KEY (saga_id, line_number)
);

REVOKE ALL ON stock, reservation, reservation_line FROM PUBLIC;
GRANT SELECT ON stock TO "${appRole}";
GRANT UPDATE (reserved, updated_at) ON stock TO "${appRole}";
GRANT SELECT, INSERT, DELETE ON reservation TO "${appRole}";
GRANT UPDATE (status, settled_at) ON reservation TO "${appRole}";
GRANT SELECT, INSERT ON reservation_line TO "${appRole}";

-- Debezium(Outbox の発行)がこの DB に接続できるようにする。DBA の初期化(postgres/init/20-debezium.sh)と同じ権限で、
-- 初期化の後に作った DB のボリュームでも、所有者のマイグレーションで付けられるようにする(何度実行しても同じ結果)
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), '${cdcRole}');
END
$$;
