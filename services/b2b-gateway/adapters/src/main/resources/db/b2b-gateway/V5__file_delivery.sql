-- 取引先へのファイルの送信の記録(P12 ⑦a。ADR-0040 §7)。ファイルと取引先の組ごとに 1 行。受け渡しの場所(outbound/)を毎回一覧し、
-- この表に記録のない組だけを送る(名前の順の位置では見ない)
CREATE TABLE b2b.file_delivery (
    name            text        NOT NULL,
    partner_id      text        NOT NULL,
    remote_name     text        NOT NULL,
    sha256          text        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    already_present boolean     NOT NULL,
    sent_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (name, partner_id)
);

COMMENT ON TABLE b2b.file_delivery IS '取引先へのファイル(INT-SALES-004)の送信の記録。ADR-0040 §7';

REVOKE ALL ON b2b.file_delivery FROM PUBLIC;
-- 送った記録は消さない・書き換えない
GRANT SELECT, INSERT ON b2b.file_delivery TO "${appRole}";
