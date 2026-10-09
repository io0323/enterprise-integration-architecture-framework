-- 決済の承認の記録(payment-service。決済の模擬。注文 Saga の参加者。ADR-0029 §5・§7)。外部の決済の代行は呼ばない。
-- DB の所有者のロールで適用する。${appRole}: アプリが接続するロール。付ける権限は次のとおり。
--   authorization_record: SELECT / INSERT / UPDATE(status と settled_at だけ)/ DELETE(保持期間を過ぎた終わった記録の削除)
-- ${cdcRole}: Debezium。この DB への CONNECT を付ける(Outbox の表の SELECT は OutboxSchema が付ける)。
-- 時刻は DB の時計(clock_timestamp())で書く。保持期間の判定も DB の時計で行う(ADR-0029 §5)。
-- 顧客 ID は記録しない(承認の判定に使わない模擬のため。データの最小化。Framework 12.2)。

-- Saga ID ごとに 1 行(業務キーの冪等。ADR-0029 §5)。VOIDED_BEFORE_AUTHORIZATION は「取消済み」の印
CREATE TABLE authorization_record (
    saga_id          varchar(64) PRIMARY KEY,
    order_id         varchar(64) NOT NULL,
    status           varchar(32) NOT NULL CHECK (status IN ('AUTHORIZED', 'DECLINED', 'VOIDED', 'VOIDED_BEFORE_AUTHORIZATION')),
    authorization_id varchar(64),
    decline_reason   varchar(32) CHECK (decline_reason IN ('LIMIT_EXCEEDED', 'ALREADY_VOIDED')),
    amount_minor     bigint      CHECK (amount_minor >= 0),
    currency         char(3)     CHECK (currency ~ '^[A-Z]{3}$'),
    created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
    -- 終わった時刻(AUTHORIZED 以外)。保持期間の起点
    settled_at       timestamptz,
    CONSTRAINT authorization_settled CHECK ((status = 'AUTHORIZED') = (settled_at IS NULL)),
    CONSTRAINT authorization_decline CHECK ((status = 'DECLINED') = (decline_reason IS NOT NULL)),
    CONSTRAINT authorization_id_present CHECK ((status IN ('AUTHORIZED', 'VOIDED')) = (authorization_id IS NOT NULL))
);

CREATE INDEX authorization_record_settled_at ON authorization_record (settled_at) WHERE settled_at IS NOT NULL;

REVOKE ALL ON authorization_record FROM PUBLIC;
GRANT SELECT, INSERT, DELETE ON authorization_record TO "${appRole}";
GRANT UPDATE (status, settled_at) ON authorization_record TO "${appRole}";

-- Debezium(Outbox の発行)がこの DB に接続できるようにする(inventory と同じ。何度実行しても同じ結果)
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), '${cdcRole}');
END
$$;
