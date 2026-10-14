-- 週次の Full 照合の結果(ADR-0031 §6)。実行の日ごとに、比べた日の結果を残す(同じ実行の再実行は上書き)。
-- 件数とハッシュだけを残す(個々の注文は残さない)。

CREATE TABLE mart.reconciliation (
    run_date      date        NOT NULL,
    business_date date        NOT NULL,
    outcome       text        NOT NULL CHECK (outcome IN ('MATCH', 'DRIFT', 'UNVERIFIED')),
    -- order-service の集計(問い合わせられなければ NULL)
    source_count  bigint,
    source_digest text CHECK (source_digest ~ '^[0-9a-f]{64}$'),
    -- 分析のパーティションの集計(まだ作っていなければ NULL)
    mart_count    bigint,
    mart_digest   text CHECK (mart_digest ~ '^[0-9a-f]{64}$'),
    checked_at    timestamptz NOT NULL,
    PRIMARY KEY (run_date, business_date)
);

COMMENT ON TABLE mart.reconciliation IS '週次の Full 照合の結果(order-service の日次の集計と、分析のパーティションの件数とハッシュ)。ADR-0031 §6';

REVOKE ALL ON mart.reconciliation FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON mart.reconciliation TO "${appRole}";
