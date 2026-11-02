-- 取引先ごとのコードの対応表(P12 ⑤a。ADR-0040 §6)。運用者のコマンド `codes import` が、種類ごとに丸ごと入れ替える
CREATE TABLE b2b.code_table (
    partner_id    text        NOT NULL,
    kind          text        NOT NULL CHECK (kind IN ('buyers', 'delivery-parties', 'items')),
    version       integer     NOT NULL CHECK (version > 0),
    rows          integer     NOT NULL CHECK (rows > 0),
    source_sha256 text        NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    imported_by   text        NOT NULL,
    imported_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (partner_id, kind)
);

COMMENT ON TABLE b2b.code_table IS '取引先ごとの対応表の版(入れ替えるたびに 1 進める)。ADR-0040 §6';

-- 買い手: GLN(NAD+BY)→ 社内の顧客と、NAD+DP がないときの既定の届け先
CREATE TABLE b2b.buyer_code (
    partner_id   text NOT NULL,
    gln          text NOT NULL CHECK (gln ~ '^[0-9]{13}$'),
    customer_id  text NOT NULL,
    country_code text NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    postal_code  text NOT NULL,
    region       text,
    city         text NOT NULL,
    line1        text NOT NULL,
    line2        text,
    PRIMARY KEY (partner_id, gln)
);

-- 届け先: GLN(NAD+DP)→ 届け先の住所
CREATE TABLE b2b.delivery_party_code (
    partner_id   text NOT NULL,
    gln          text NOT NULL CHECK (gln ~ '^[0-9]{13}$'),
    country_code text NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    postal_code  text NOT NULL,
    region       text,
    city         text NOT NULL,
    line1        text NOT NULL,
    line2        text,
    PRIMARY KEY (partner_id, gln)
);

-- 商品: 取引先の商品コード(PIA の BP)か GTIN(LIN)→ 社内の商品
CREATE TABLE b2b.item_code (
    partner_id text NOT NULL,
    code_type  text NOT NULL CHECK (code_type IN ('gtin', 'buyer_part_number')),
    code       text NOT NULL,
    product_id text NOT NULL,
    sku        text NOT NULL,
    PRIMARY KEY (partner_id, code_type, code)
);

REVOKE ALL ON b2b.code_table, b2b.buyer_code, b2b.delivery_party_code, b2b.item_code FROM PUBLIC;
-- 対応表は入れ替える(DELETE と INSERT)。版は進める(UPDATE)
GRANT SELECT, INSERT, UPDATE ON b2b.code_table TO "${appRole}";
GRANT SELECT, INSERT, DELETE ON b2b.buyer_code, b2b.delivery_party_code, b2b.item_code TO "${appRole}";
