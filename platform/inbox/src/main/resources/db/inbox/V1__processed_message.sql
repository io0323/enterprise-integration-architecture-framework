-- 冪等消費の記録(Framework 6.4・13.2。ADR-0028 §3)。
-- platform/inbox の InboxSchema が、サービスの DB の所有者のロールで、専用の履歴の表(inbox.inbox_schema_history)を使って適用する。
-- ${appRole}: アプリが接続するロール。INSERT(記録)・DELETE(保持期間を過ぎた行の削除)と、その条件に使う列の SELECT だけを付ける。
--
-- アプリは業務の更新と同じトランザクションで INSERT ... ON CONFLICT DO NOTHING を実行し、挿入できなければ重複として処理を捨てる。
-- 時刻は DB の時計(clock_timestamp())で記録する。アプリのインスタンスの時計のずれで、保持期間の判定がずれないようにする。

CREATE TABLE processed_message (
    -- Kafka の Consumer Group({service}.{purpose}。INTEGRATION_STANDARDS §1)。同じメッセージでも、グループごとに 1 回ずつ処理する
    consumer_group text        NOT NULL CHECK (consumer_group ~ '^[a-z][a-z0-9-]*\.[a-z][a-z0-9-]*$'),
    -- ce_id(CloudEvents のイベント ID)
    message_id     uuid        NOT NULL,
    -- 調査用(どのトピックのメッセージか)。判定には使わない
    topic          text        NOT NULL CHECK (topic <> ''),
    processed_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (consumer_group, message_id)
);

-- 保持期間を過ぎた行の削除(古い順)に使う
CREATE INDEX processed_message_processed_at ON processed_message (processed_at);

COMMENT ON TABLE processed_message IS '冪等消費の記録。業務の更新と同じトランザクションで INSERT し、重複を捨てる。保持期間を過ぎた行は削除する。ADR-0028';

-- 所有者以外の権限は明示したものだけにする(UPDATE・TRUNCATE は付けない)
REVOKE ALL ON processed_message FROM PUBLIC;
GRANT USAGE ON SCHEMA inbox TO "${appRole}";
GRANT INSERT, DELETE ON processed_message TO "${appRole}";
GRANT SELECT (consumer_group, message_id, processed_at) ON processed_message TO "${appRole}";
