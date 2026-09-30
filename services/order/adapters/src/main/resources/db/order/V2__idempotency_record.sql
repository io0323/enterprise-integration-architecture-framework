-- Idempotency-Key の記録(ADR-0022 §3。platform/api の IdempotencyStore の PostgreSQL の実装)。
-- ${appRole} には SELECT / INSERT / UPDATE / DELETE を付ける(処理中の記録の取り消しと、期限切れの記録の削除のため)。
-- リースと保持期限は DB の時刻(clock_timestamp())で設定・判定する。アプリの時計は使わない(複数のインスタンスの時計のずれ対策)。

CREATE TABLE idempotency_record (
    client_id        varchar(255) NOT NULL,
    idem_key         varchar(255) NOT NULL,
    -- 要求の指紋(SHA-256 の 16 進)
    fingerprint      char(64)     NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    state            varchar(16)  NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    -- 処理中: 所有者のトークン(フェンシング)とリースの期限
    lease_token      varchar(64),
    lease_expires_at timestamptz,
    -- 完了: 保存した応答(状態コード・許可したヘッダ・本文)と保持期限
    response_status  integer,
    response_headers jsonb,
    response_body    bytea,
    expires_at       timestamptz,
    created_at       timestamptz  NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (client_id, idem_key),
    CHECK (
        (state = 'IN_PROGRESS' AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL
            AND response_status IS NULL AND expires_at IS NULL)
        OR (state = 'COMPLETED' AND lease_token IS NULL AND lease_expires_at IS NULL
            AND response_status IS NOT NULL AND response_headers IS NOT NULL AND response_body IS NOT NULL AND expires_at IS NOT NULL)
    )
);

-- 期限切れの記録の削除(purgeExpired)のための索引
CREATE INDEX idempotency_record_completed_expiry ON idempotency_record (expires_at) WHERE state = 'COMPLETED';
CREATE INDEX idempotency_record_lease_expiry ON idempotency_record (lease_expires_at) WHERE state = 'IN_PROGRESS';

REVOKE ALL ON idempotency_record FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON idempotency_record TO "${appRole}";
