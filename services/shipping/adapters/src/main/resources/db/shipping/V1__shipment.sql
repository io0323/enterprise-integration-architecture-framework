-- 出荷の記録(shipping-service。出荷の模擬。注文 Saga の参加者。ADR-0029 §5・§7)。運送の手配は行わない。
-- DB の所有者のロールで適用する。${appRole}: アプリが接続するロール。付ける権限は次のとおり。
--   shipment: SELECT / INSERT / DELETE(保持期間を過ぎた終わった記録の削除)。出荷の記録は書き換えない(UPDATE なし)
-- ${cdcRole}: Debezium。この DB への CONNECT を付ける(Outbox の表の SELECT は OutboxSchema が付ける)。
-- 時刻は DB の時計(clock_timestamp())で書く。保持期間の判定も DB の時計で行う(ADR-0029 §5)。
-- 届け先は国だけを記録する(模擬は運送を手配しないため。データの最小化。Framework 12.2)。

-- Saga ID ごとに 1 行(業務キーの冪等。ADR-0029 §5)。CANCELLED_BEFORE_ARRANGEMENT は「取消済み」の印
CREATE TABLE shipment (
    saga_id             varchar(64) PRIMARY KEY,
    order_id            varchar(64) NOT NULL,
    status              varchar(32) NOT NULL CHECK (status IN ('SHIPPED', 'REJECTED', 'CANCELLED_BEFORE_ARRANGEMENT')),
    shipment_id         varchar(64),
    shipped_at          timestamptz,
    rejection_reason    varchar(32) CHECK (rejection_reason IN ('UNSUPPORTED_DESTINATION', 'ALREADY_CANCELLED')),
    destination_country char(2)     CHECK (destination_country ~ '^[A-Z]{2}$'),
    created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    -- 終わった時刻(SHIPPED 以外)。保持期間の起点
    settled_at          timestamptz,
    CONSTRAINT shipment_settled CHECK ((status = 'SHIPPED') = (settled_at IS NULL)),
    CONSTRAINT shipment_shipped CHECK ((status = 'SHIPPED') = (shipment_id IS NOT NULL AND shipped_at IS NOT NULL)),
    CONSTRAINT shipment_rejection CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL))
);

CREATE INDEX shipment_settled_at ON shipment (settled_at) WHERE settled_at IS NOT NULL;

REVOKE ALL ON shipment FROM PUBLIC;
GRANT SELECT, INSERT, DELETE ON shipment TO "${appRole}";

-- Debezium(Outbox の発行)がこの DB に接続できるようにする(inventory と同じ。何度実行しても同じ結果)
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), '${cdcRole}');
END
$$;
