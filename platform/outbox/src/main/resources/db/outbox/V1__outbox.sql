-- Transactional Outbox(Framework 8.3。ADR-0007)。
-- platform/outbox の OutboxSchema が、サービスの DB の所有者のロールで、専用の履歴の表(outbox.outbox_schema_history)を使って適用する。
-- ${appRole}: アプリが接続するロール。INSERT・DELETE と、DELETE の条件に使う id 列の SELECT だけを付ける。
-- ${cdcRole}: Debezium のロール(REPLICATION を持つ)。SELECT だけを付ける。
--
-- 既定の方式: アプリは業務の更新と同じトランザクションで INSERT し、直後に同じ行を DELETE する。表に行は残らない(ADR-0007 §2)。

CREATE TABLE outbox (
    id             uuid        PRIMARY KEY,
    topic          text        NOT NULL CHECK (topic ~ '^([a-z][a-z0-9]*(-[a-z0-9]+)*\.){3}v[1-9][0-9]*$'),
    aggregate_type text        NOT NULL CHECK (aggregate_type <> ''),
    aggregate_id   text        NOT NULL CHECK (aggregate_id <> ''),
    event_type     text        NOT NULL CHECK (event_type <> ''),
    payload        bytea       NOT NULL,
    traceparent    text        NOT NULL,
    correlation_id text        NOT NULL,
    -- イベントの ID は 1 つ(id と同じ値。ADR-0007 の改訂履歴 2026-10-07)
    ce_id          uuid        NOT NULL CHECK (ce_id = id),
    ce_source      text        NOT NULL,
    ce_time        timestamptz NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE outbox IS 'Transactional Outbox。INSERT の直後に同じトランザクションで DELETE する(行は残らない)。発行は Debezium の Outbox Event Router。ADR-0007';

-- 所有者以外の権限は明示したものだけにする
REVOKE ALL ON outbox FROM PUBLIC;
GRANT USAGE ON SCHEMA outbox TO "${appRole}";
GRANT INSERT, DELETE ON outbox TO "${appRole}";
GRANT SELECT (id) ON outbox TO "${appRole}";
GRANT USAGE ON SCHEMA outbox TO "${cdcRole}";
GRANT SELECT ON outbox TO "${cdcRole}";

-- Debezium(pgoutput)が読む publication。Outbox の表だけを含める(業務の表は発行しない)
CREATE PUBLICATION "${publication}" FOR TABLE outbox;
