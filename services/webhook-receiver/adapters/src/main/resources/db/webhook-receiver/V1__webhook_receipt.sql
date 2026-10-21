-- Webhook の受け取りの記録(webhook-receiver。ADR-0035 §3)。重複の判定(webhook_id)と調査に要るものだけを持つ。
-- **本文の列は持たない**(顧客の名前・メールなどの個人情報の置き場所を増やさない)。本文は Outbox を通して取り込みのトピックに流すだけ。
-- DB の所有者のロールで適用する。${appRole}: アプリが接続するロール。付ける権限は次のとおり。
--   webhook_receipt: SELECT / INSERT / DELETE(保持期間を過ぎた記録の削除)。記録は書き換えない(UPDATE なし)
-- ${cdcRole}: Debezium。この DB への CONNECT を付ける(Outbox の表の SELECT は OutboxSchema が付ける)。

CREATE TABLE webhook_receipt (
    webhook_id  varchar(128) PRIMARY KEY CHECK (webhook_id ~ '^[A-Za-z0-9_.:-]+$'),
    source      varchar(32)  NOT NULL CHECK (source ~ '^[a-z][a-z0-9-]*$'),
    body_sha256 char(64)     NOT NULL CHECK (body_sha256 ~ '^[0-9a-f]{64}$'),
    signed_at   timestamptz  NOT NULL,
    received_at timestamptz  NOT NULL
);

-- 保持期間を過ぎた記録の削除(古い順)
CREATE INDEX webhook_receipt_received_at ON webhook_receipt (received_at);

REVOKE ALL ON webhook_receipt FROM PUBLIC;
GRANT SELECT, INSERT, DELETE ON webhook_receipt TO "${appRole}";

-- Debezium(Outbox の発行)がこの DB に接続できるようにする(何度実行しても同じ結果)
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), '${cdcRole}');
END
$$;
