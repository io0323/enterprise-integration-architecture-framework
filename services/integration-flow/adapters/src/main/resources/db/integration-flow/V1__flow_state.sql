-- integration-flow の状態(ADR-0036 §3)。DB の所有者のロールで適用する。${appRole}: アプリが接続するロール。
-- ${cdcRole}: Debezium。この DB への CONNECT を付ける(Outbox の表の SELECT は OutboxSchema が付ける)。
-- 顧客の名前・メールなどの値は持たない(キーと版と時刻だけ)。

-- 新旧の判定の記録: Flow とキーごとの最新の版。Webhook の経路と定期の同期の経路が同じ記録を使う(ADR-0035 §4)
CREATE TABLE flow_version (
    flow_id    varchar(63)  NOT NULL CHECK (flow_id ~ '^[a-z][a-z0-9-]*$'),
    record_key varchar(256) NOT NULL CHECK (record_key <> ''),
    version    bigint       NOT NULL CHECK (version >= 0),
    updated_at timestamptz  NOT NULL,
    PRIMARY KEY (flow_id, record_key)
);

-- 定期の同期の Watermark(前の同期を始めた時刻から重なりの分を引いた時刻。Framework 7 の Checkpoint)
CREATE TABLE sync_watermark (
    flow_id    varchar(63)  NOT NULL CHECK (flow_id ~ '^[a-z][a-z0-9-]*$'),
    connector  varchar(32)  NOT NULL CHECK (connector ~ '^INT-[A-Z][A-Z0-9]*-[0-9]{3}$'),
    watermark  timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    PRIMARY KEY (flow_id, connector)
);

REVOKE ALL ON flow_version, sync_watermark FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON flow_version, sync_watermark TO "${appRole}";

-- Debezium(Outbox の発行)がこの DB に接続できるようにする(何度実行しても同じ結果)
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), '${cdcRole}');
END
$$;
