-- 注文の分析(ADR-0031)。batch-etl の DB の所有者(batch_etl)が適用する。
-- ${appRole}: バッチが接続するロール(batch_etl_app)。読み書きだけを付ける(DDL・TRUNCATE は付けない)。
--
-- raw : 取り込んだイベント(ELT の元。個人情報を落としてから入れる = ETL)。行は冪等(同じイベントは 1 回だけ)
-- etl : 作り直しの印(データが変わった業務の日)
-- mart: 日次のパーティション(業務の日 = 日本時間。ADR-0031 §1)。日ごとに置換する

CREATE TABLE raw.order_placed (
    order_id           text        PRIMARY KEY,
    customer_id        text        NOT NULL,
    ordered_at         timestamptz NOT NULL,
    -- 受け付けた業務の日(日本時間)。アプリ(domain の BusinessDay)で決める
    business_date      date        NOT NULL,
    total_minor_units  bigint      NOT NULL,
    currency           text        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    line_count         integer     NOT NULL CHECK (line_count >= 0),
    -- 届け先は地域だけ(国・都道府県・郵便番号の先頭 3 桁)。市区町村・番地・建物は取り込まない
    ship_country       text        NOT NULL,
    ship_region        text,
    ship_postal_prefix text        CHECK (ship_postal_prefix ~ '^[0-9]{3}$'),
    ingested_at        timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX order_placed_business_date ON raw.order_placed (business_date);

CREATE TABLE raw.order_status_change (
    order_id    text        NOT NULL,
    -- 遷移の後の版(楽観的ロックの版)。大きい方が新しい(届く順に依存しない。ADR-0031 §2)
    version     bigint      NOT NULL,
    status      text        NOT NULL CHECK (status IN ('PLACED', 'CONFIRMED', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    changed_at  timestamptz NOT NULL,
    ingested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (order_id, version)
);

CREATE TABLE raw.order_cancelled (
    order_id     text        PRIMARY KEY,
    cancelled_at timestamptz NOT NULL,
    reason       text        NOT NULL,
    ingested_at  timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE etl.dirty_day (
    business_date date        PRIMARY KEY,
    marked_at     timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE mart.order_daily (
    business_date     date        NOT NULL,
    order_id          text        NOT NULL,
    customer_id       text        NOT NULL,
    ordered_at        timestamptz NOT NULL,
    -- 今の状態(版の最も大きい遷移。遷移がなければ PLACED)
    status            text        NOT NULL,
    status_changed_at timestamptz,
    cancel_reason     text,
    total_minor_units bigint      NOT NULL,
    currency          text        NOT NULL,
    line_count        integer     NOT NULL,
    ship_country      text        NOT NULL,
    ship_region       text,
    PRIMARY KEY (business_date, order_id)
);

-- パーティションを最後に作った時刻と件数(運用の確認・照合の対象の判定)
CREATE TABLE mart.partition_build (
    business_date date        PRIMARY KEY,
    order_count   integer     NOT NULL,
    built_at      timestamptz NOT NULL
);

COMMENT ON TABLE raw.order_placed IS '受け付けた注文(sales.order.created.v1)。住所は地域だけ。ADR-0031';
COMMENT ON TABLE etl.dirty_day IS 'データが変わり、パーティションの作り直しが要る業務の日。ADR-0031 §5';
COMMENT ON TABLE mart.order_daily IS '注文の日次のパーティション(業務の日 = 日本時間)。日ごとに置換する。ADR-0031';

REVOKE ALL ON ALL TABLES IN SCHEMA raw, etl, mart FROM PUBLIC;
GRANT USAGE ON SCHEMA raw, etl, mart TO "${appRole}";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA raw, etl, mart TO "${appRole}";
