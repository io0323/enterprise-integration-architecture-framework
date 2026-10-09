-- 注文 Saga の記録(Orchestration。docs/architecture/order-saga.md・ADR-0029 §1・§6)。
-- DB の所有者のロールで適用する。${appRole}: アプリが接続するロール。付ける権限は次のとおり。
--   order_saga: SELECT / INSERT / UPDATE(state・failure・resends・deadline_at・updated_at だけ)。DELETE はしない
--
-- 段の期限(deadline_at)は DB の時計で書き(clock_timestamp() + 段の期限)、期限切れの判定も DB の時計で行う
-- (アプリのインスタンスの時計を使わない。P05 の冪等のリースと同じ理由。ADR-0029 §6)。終端(COMPLETED・COMPENSATED)は期限を持たない。

CREATE TABLE order_saga (
    saga_id     varchar(64) PRIMARY KEY,
    -- 注文 1 件に Saga 1 つ(P07。取消の Saga などを入れるときは、この一意性を見直す。ADR-0029 §1)
    order_id    varchar(64) NOT NULL UNIQUE REFERENCES orders (id),
    state       varchar(32) NOT NULL CHECK (state IN (
                    'RESERVING_STOCK', 'AUTHORIZING_PAYMENT', 'ARRANGING_SHIPMENT', 'COMPLETED',
                    'CANCELLING_SHIPMENT', 'VOIDING_PAYMENT', 'RELEASING_STOCK', 'COMPENSATED')),
    failure     varchar(32) CHECK (failure IN ('STOCK_UNAVAILABLE', 'PAYMENT_DECLINED', 'SHIPMENT_REJECTED', 'TIMED_OUT')),
    -- 今の補償の段で、コマンドを送り直した回数(アラートに使う)
    resends     integer     NOT NULL DEFAULT 0 CHECK (resends >= 0),
    deadline_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT order_saga_deadline CHECK ((state IN ('COMPLETED', 'COMPENSATED')) = (deadline_at IS NULL))
);

-- 期限切れの検出(終端でない Saga だけ)
CREATE INDEX order_saga_deadline_at ON order_saga (deadline_at) WHERE deadline_at IS NOT NULL;

REVOKE ALL ON order_saga FROM PUBLIC;
GRANT SELECT, INSERT ON order_saga TO "${appRole}";
GRANT UPDATE (state, failure, resends, deadline_at, updated_at) ON order_saga TO "${appRole}";
