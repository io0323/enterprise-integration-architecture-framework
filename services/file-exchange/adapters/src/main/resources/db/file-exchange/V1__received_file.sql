-- file-exchange の受け取りの記録(ADR-0032 §5)。スキーマ exchange は Flyway が作る。

-- 受け取ったファイル(ファイル名ごとに 1 行)。移動の決定を MOVING で記録してから移し、移し終えたら STORED にする。
-- 主キーで、同じ名前を 2 つ記録できない(先に記録した決定に従う)。
CREATE TABLE exchange.received_file (
    file_name   text        PRIMARY KEY CHECK (file_name ~ '^[a-z][a-z0-9-]*_[a-z][a-z0-9-]*_[0-9]{14}_[0-9]{3,}$'),
    sha256      text        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    destination text        NOT NULL CHECK (destination IN ('ARCHIVE', 'QUARANTINE')),
    data_file   text,
    reason      text,
    detail      text,
    state       text        NOT NULL CHECK (state IN ('MOVING', 'STORED')),
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    stored_at   timestamptz,
    CHECK ((destination = 'ARCHIVE') = (reason IS NULL)),
    CHECK (destination = 'QUARANTINE' OR data_file IS NOT NULL),
    CHECK ((state = 'STORED') = (stored_at IS NOT NULL))
);

-- 受け取り済みの名前で、また届いたファイル(送り直し・名前の使い回し)。同じ名前で処理中(MOVING)のものは 1 つだけ。
CREATE TABLE exchange.extra_arrival (
    id          bigserial   PRIMARY KEY,
    file_name   text        NOT NULL,
    sha256      text        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    reason      text        NOT NULL CHECK (reason IN ('DUPLICATE', 'NAME_REUSED')),
    state       text        NOT NULL CHECK (state IN ('MOVING', 'DONE')),
    detected_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    done_at     timestamptz,
    CHECK ((state = 'DONE') = (done_at IS NOT NULL))
);
CREATE UNIQUE INDEX extra_arrival_one_moving ON exchange.extra_arrival (file_name) WHERE state = 'MOVING';

COMMENT ON TABLE exchange.received_file IS '受け取ったファイルと移動の決定。ADR-0032 §5';
COMMENT ON TABLE exchange.extra_arrival IS '受け取り済みの名前で届いたファイル。ADR-0032 §5';

REVOKE ALL ON ALL TABLES IN SCHEMA exchange FROM PUBLIC;
GRANT USAGE ON SCHEMA exchange TO "${appRole}";
-- 記録は消さない(DELETE を付けない)
GRANT SELECT, INSERT, UPDATE ON exchange.received_file, exchange.extra_arrival TO "${appRole}";
GRANT USAGE ON SEQUENCE exchange.extra_arrival_id_seq TO "${appRole}";
