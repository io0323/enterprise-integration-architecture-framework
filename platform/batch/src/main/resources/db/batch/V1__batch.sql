-- バッチの実行の記録・Checkpoint(Framework 7.4。ADR-0030 §2・§3)。
-- platform/batch の BatchSchema が、バッチの DB の所有者のロールで、専用の履歴の表(batch.batch_schema_history)を使って適用する。
-- ${appRole}: バッチが接続するロール。読み書き(SELECT・INSERT・UPDATE・DELETE)だけを付ける(TRUNCATE・DDL は付けない)。
--
-- 時刻はアプリの時計で記録する(所要時間とテストの時刻を揃えるため)。同じ実行を 2 つのインスタンスで動かさないためのロックは、
-- 表ではなく PostgreSQL の advisory lock(セッション)で取る。プロセスが落ちれば接続が切れてロックが外れる。

CREATE TABLE batch_run (
    -- {service}.{job}
    dag_id              text        NOT NULL CHECK (dag_id ~ '^[a-z][a-z0-9-]*\.[a-z][a-z0-9-]*$'),
    -- 業務の日(yyyy-MM-dd)・ファイルの名前
    partition_key       text        NOT NULL CHECK (partition_key ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'),
    status              text        NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    -- やり直し(RESTART)のたびに 1 増える
    generation          integer     NOT NULL CHECK (generation >= 1),
    -- 起動の情報(ジョブの属性)。再開しても最初の値を使う
    trigger_kind        text        NOT NULL CHECK (trigger_kind IN ('SCHEDULE', 'UPSTREAM', 'FILE', 'MANUAL')),
    trigger_traceparent text,
    correlation_id      text,
    attributes          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    -- 最後に実行した span(後段の DAG の span の親にする)
    run_traceparent     text,
    started_at          timestamptz NOT NULL,
    finished_at         timestamptz,
    PRIMARY KEY (dag_id, partition_key)
);

-- 前段の完了の検索・SLA の予測の対象の検索
CREATE INDEX batch_run_status ON batch_run (dag_id, status, started_at);

CREATE TABLE batch_step (
    dag_id        text        NOT NULL,
    partition_key text        NOT NULL,
    step_name     text        NOT NULL CHECK (step_name ~ '^[a-z][a-z0-9-]{0,62}$'),
    status        text        NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    -- 今の起動での試行の回数
    attempts      integer     NOT NULL CHECK (attempts >= 1),
    -- 今の起動での最初の試行の開始(所要時間はリトライを含む)
    started_at    timestamptz NOT NULL,
    finished_at   timestamptz,
    PRIMARY KEY (dag_id, partition_key, step_name),
    FOREIGN KEY (dag_id, partition_key) REFERENCES batch_run (dag_id, partition_key) ON DELETE CASCADE
);

-- 所要時間の実績(SLA の予測の p90)の検索
CREATE INDEX batch_step_history ON batch_step (dag_id, step_name, finished_at DESC) WHERE status = 'SUCCEEDED';

CREATE TABLE batch_checkpoint (
    dag_id        text        NOT NULL,
    -- 空文字は DAG 全体の Watermark(パーティションをまたいで残る)。それ以外はパーティションのステップの途中の位置
    partition_key text        NOT NULL,
    name          text        NOT NULL CHECK (name ~ '^[a-z0-9][a-z0-9._-]{0,127}$'),
    value         text        NOT NULL,
    updated_at    timestamptz NOT NULL,
    PRIMARY KEY (dag_id, partition_key, name)
);

COMMENT ON TABLE batch_run IS 'バッチの実行(DAG とパーティション)の記録。ADR-0030';
COMMENT ON TABLE batch_step IS 'バッチのステップの記録。成功したステップは再実行で飛ばす。ADR-0030';
COMMENT ON TABLE batch_checkpoint IS 'バッチの Checkpoint と Watermark。ADR-0030';

REVOKE ALL ON batch_run, batch_step, batch_checkpoint FROM PUBLIC;
GRANT USAGE ON SCHEMA batch TO "${appRole}";
GRANT SELECT, INSERT, UPDATE, DELETE ON batch_run, batch_step, batch_checkpoint TO "${appRole}";
