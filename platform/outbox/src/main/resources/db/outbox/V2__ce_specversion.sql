-- CloudEvents の specversion(ADR-0007 の改訂履歴 2026-10-07)。
-- Debezium の Outbox Event Router は列の値だけをヘッダに載せられる。Kafka Connect の InsertHeader の固定値は数として解釈され
-- "1.0" が 1 になるため、ほかの ce_* と同じく列からヘッダ(ce_specversion)に載せる。アプリは値を書かない(既定値)。
ALTER TABLE outbox ADD COLUMN ce_specversion text NOT NULL DEFAULT '1.0' CHECK (ce_specversion = '1.0');
