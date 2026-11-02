-- 受け付けた注文の社内への取り込み(P12 ⑤b。ADR-0040 §6)。受け付けの時点で、受け取りの記録と同じトランザクションで書く
CREATE TABLE b2b.order_submission (
    id                  uuid        PRIMARY KEY,
    document_id         uuid        NOT NULL REFERENCES b2b.received_document (id),
    partner_id          text        NOT NULL,
    order_number        text        NOT NULL,
    message_reference   text        NOT NULL,
    -- order の API の Idempotency-Key(b2b:{取引先}:{0020}:{0062})と、受け付けの時点で固定した要求の本文
    idempotency_key     text        NOT NULL UNIQUE,
    body                bytea       NOT NULL,
    body_sha256         text        NOT NULL CHECK (body_sha256 ~ '^[0-9a-f]{64}$'),
    -- 業務の応答(APERAK)を、受け取った交換の区切り文字・文字セット・宛先で組み立てるための UNB
    service_characters  text        NOT NULL CHECK (length(service_characters) = 9),
    character_set       text        NOT NULL,
    syntax_version      text        NOT NULL,
    sender_id           text        NOT NULL,
    sender_qualifier    text,
    recipient_id        text        NOT NULL,
    recipient_qualifier text,
    control_reference   text        NOT NULL,
    positive_aperak     boolean     NOT NULL,
    aperak_deadline     timestamptz NOT NULL,
    retry_until         timestamptz NOT NULL,
    state               text        NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'ACCEPTED', 'REFUSED', 'EXPIRED')),
    attempts            integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error          text,
    order_id            text,
    refused_status      integer,
    created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at        timestamptz,
    CHECK ((state = 'PENDING') = (completed_at IS NULL)),
    CHECK ((state = 'ACCEPTED') = (order_id IS NOT NULL)),
    CHECK ((state = 'REFUSED') = (refused_status IS NOT NULL))
);

CREATE INDEX order_submission_pending ON b2b.order_submission (created_at) WHERE state = 'PENDING';

COMMENT ON TABLE b2b.order_submission IS '受け付けた注文の社内への取り込み(order の API)の送信待ちと結果。ADR-0040 §6';

REVOKE ALL ON b2b.order_submission FROM PUBLIC;
-- 送信の結果を書く(UPDATE)。消さない
GRANT SELECT, INSERT, UPDATE ON b2b.order_submission TO "${appRole}";
