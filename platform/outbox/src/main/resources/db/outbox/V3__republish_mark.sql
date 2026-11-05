-- 再発行の目印(ADR-0046)。ふつうのイベントでは 3 つとも NULL。分析の側で元のイベントと区別できるよう、発行するコネクタがヘッダにする。
ALTER TABLE outbox
    ADD COLUMN republished      text,
    ADD COLUMN republish_reason text,
    ADD COLUMN republish_run_id text,
    ADD CONSTRAINT outbox_republish_mark CHECK (
        (republished IS NULL AND republish_reason IS NULL AND republish_run_id IS NULL)
        OR (republished = 'true' AND republish_reason IS NOT NULL AND republish_run_id IS NOT NULL)
    );
