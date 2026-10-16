-- 取引先への配送の記録(ADR-0032 §8)。受け取ったファイルと取引先の組ごとに 1 行。
-- 送る前に SENDING を記録し、送り終えたら SENT にする。SENDING のまま残った組は、やり直しで相手の側に正式名があるかを確かめてから送る
-- (正式名に変えた後、SENT を記録する前に落ちても、同じファイルを送り直さない)。
CREATE TABLE exchange.delivery (
    file_name       text        NOT NULL REFERENCES exchange.received_file (file_name),
    partner_id      text        NOT NULL CHECK (partner_id ~ '^[a-z0-9][a-z0-9-]{0,31}$'),
    remote_name     text        NOT NULL,
    state           text        NOT NULL CHECK (state IN ('SENDING', 'SENT')),
    already_present boolean     NOT NULL DEFAULT false,
    started_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    sent_at         timestamptz,
    PRIMARY KEY (file_name, partner_id),
    CHECK ((state = 'SENT') = (sent_at IS NOT NULL)),
    CHECK (state = 'SENT' OR NOT already_present)
);

COMMENT ON TABLE exchange.delivery IS '取引先への配送。ADR-0032 §8';

REVOKE ALL ON exchange.delivery FROM PUBLIC;
-- 記録は消さない(DELETE を付けない)
GRANT SELECT, INSERT, UPDATE ON exchange.delivery TO "${appRole}";
