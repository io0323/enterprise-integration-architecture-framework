-- 受け取った文書の記録(P12 ④a。ADR-0040 §5・§8、ADR-0041 §11)。SFTP のファイル 1 つに 1 行。読めない文書(復号の段階で拒否)も記録する。
-- 原本(PGP の暗号文)は S3 互換ストレージの Object Lock に置き、ここには場所と SHA-256 だけを持つ(本文は持たない)。
-- 重複の判定のキー(取引先・UNB の送り手・受け手・交換の管理番号)ごとに、初めての受け取り(first_receipt)は 1 行だけ(部分一意索引)。
CREATE TABLE b2b.received_document (
    id                  uuid        PRIMARY KEY,
    partner_id          text        NOT NULL CHECK (partner_id ~ '^[a-z0-9][a-z0-9-]{0,31}$'),
    file_name           text        NOT NULL,
    received_at         timestamptz NOT NULL,
    original_ref        text        NOT NULL,
    original_sha256     text        NOT NULL CHECK (original_sha256 ~ '^[0-9a-f]{64}$'),
    outcome             text        NOT NULL CHECK (outcome IN ('UNREADABLE', 'NO_ENVELOPE', 'REJECTED', 'CHECKED', 'RESENT')),
    stage               text        NOT NULL CHECK (stage IN ('decryption', 'syntax', 'schema', 'business', 'none')),
    refusal             text,
    interchange_sha256  text        CHECK (interchange_sha256 ~ '^[0-9a-f]{64}$'),
    signer              text,
    readable_ref        text,
    sender_id           text,
    sender_qualifier    text,
    recipient_id        text,
    recipient_qualifier text,
    control_reference   text,
    test                boolean     NOT NULL DEFAULT false,
    first_receipt       boolean     NOT NULL,
    previous_document   uuid        REFERENCES b2b.received_document (id),
    CHECK ((outcome = 'UNREADABLE') = (refusal IS NOT NULL)),
    CHECK ((outcome = 'UNREADABLE') = (interchange_sha256 IS NULL)),
    CHECK (NOT first_receipt OR control_reference IS NOT NULL),
    CHECK ((outcome = 'RESENT') = (previous_document IS NOT NULL))
);

CREATE UNIQUE INDEX received_document_first_receipt
    ON b2b.received_document (partner_id, sender_id, sender_qualifier, recipient_id, recipient_qualifier, control_reference)
    WHERE first_receipt;

COMMENT ON TABLE b2b.received_document IS '受け取った文書(原本の場所と SHA-256・検査の結果)。ADR-0040 §5・ADR-0041 §11';

-- 受け付けた注文(業務の段階の注文番号の重複。ORD007)。試験の交換の注文は入れない(社内に取り込まないため)
CREATE TABLE b2b.received_order (
    partner_id        text        NOT NULL,
    order_number      text        NOT NULL,
    document_id       uuid        NOT NULL REFERENCES b2b.received_document (id),
    message_reference text        NOT NULL,
    received_at       timestamptz NOT NULL,
    PRIMARY KEY (partner_id, order_number)
);

COMMENT ON TABLE b2b.received_order IS '受け付けた注文(注文番号の重複の判定。社内への取り込みは P12 ⑤)。ADR-0041 §11';

-- 取引先に送る応答(CONTRL・APERAK)。原本(取引先あての暗号文)の場所を持ち、送信の仕組み(P12 ④b)が送る。
-- 送り直し(同じ交換の再送)では、前の記録の応答と同じ原本を指す行を足す
CREATE TABLE b2b.acknowledgement (
    id              uuid        PRIMARY KEY,
    document_id     uuid        NOT NULL REFERENCES b2b.received_document (id),
    partner_id      text        NOT NULL,
    kind            text        NOT NULL CHECK (kind IN ('CONTRL', 'APERAK')),
    original_ref    text        NOT NULL,
    original_sha256 text        NOT NULL CHECK (original_sha256 ~ '^[0-9a-f]{64}$'),
    readable_ref    text        NOT NULL,
    state           text        NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'SENT')),
    created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    sent_at         timestamptz,
    CHECK ((state = 'SENT') = (sent_at IS NOT NULL))
);

CREATE INDEX acknowledgement_pending ON b2b.acknowledgement (partner_id, created_at) WHERE state = 'PENDING';

COMMENT ON TABLE b2b.acknowledgement IS '取引先に送る応答(原本の場所)。ADR-0040 §7・ADR-0041 §5';

-- 応答の交換の管理番号(UNB / UNZ の 0020)。宛先の取引先の識別子ごとに 1 ずつ増やす
CREATE TABLE b2b.control_reference (
    party_id        text   NOT NULL,
    party_qualifier text   NOT NULL,
    last_value      bigint NOT NULL CHECK (last_value > 0 AND last_value < 100000000000000),
    PRIMARY KEY (party_id, party_qualifier)
);

COMMENT ON TABLE b2b.control_reference IS '応答の交換の管理番号(an..14)。ADR-0041 §5';

REVOKE ALL ON b2b.received_document, b2b.received_order, b2b.acknowledgement, b2b.control_reference FROM PUBLIC;
-- 受け取りの記録と注文は消さない・書き換えない(INSERT と SELECT だけ)。応答は送った印を付ける(UPDATE)。管理番号は増やす(UPDATE)
GRANT SELECT, INSERT ON b2b.received_document, b2b.received_order TO "${appRole}";
GRANT SELECT, INSERT, UPDATE ON b2b.acknowledgement, b2b.control_reference TO "${appRole}";
GRANT USAGE ON SCHEMA b2b TO "${appRole}";
