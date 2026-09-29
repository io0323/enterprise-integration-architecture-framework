-- 監査記録(追記専用 + ハッシュチェーン。ADR-0008・ADR-0017)。
-- platform/audit の AuditSchema が、サービスの DB の所有者のロールで、専用の履歴テーブル(audit.audit_schema_history)を使って適用する。
-- ${appRole}: アプリが接続するロール。INSERT と SELECT だけを付ける(UPDATE / DELETE / TRUNCATE は付けない)。

CREATE TABLE audit_log (
    seq               bigint      PRIMARY KEY CHECK (seq >= 1),
    canonical_version smallint    NOT NULL CHECK (canonical_version >= 1),
    occurred_at       timestamptz NOT NULL,
    recorded_at       timestamptz NOT NULL,
    actor_type        text        NOT NULL,
    actor_id          text        NOT NULL,
    action            text        NOT NULL,
    target_type       text        NOT NULL,
    target_id         text,
    destination       text,
    outcome           text        NOT NULL,
    payload_sha256    text        CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    payload_ref       text,
    correlation_id    text,
    traceparent       text,
    details           jsonb       NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details) = 'object'),
    prev_hash         text        NOT NULL CHECK (prev_hash ~ '^[0-9a-f]{64}$'),
    hash              text        NOT NULL UNIQUE CHECK (hash ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE audit_log IS '監査記録(追記専用・ハッシュチェーン)。UPDATE / DELETE / TRUNCATE はトリガーで拒否する。ADR-0017';

-- 所有者以外の権限は明示したものだけにする
REVOKE ALL ON audit_log FROM PUBLIC;
GRANT USAGE ON SCHEMA audit TO "${appRole}";
GRANT SELECT, INSERT ON audit_log TO "${appRole}";

-- 権限に加えて、所有者・superuser の誤操作もトリガーで拒否する(トリガーを外した改竄はハッシュチェーンとアンカーで検出する)
CREATE FUNCTION reject_audit_log_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit.audit_log は追記専用です(% は許可されていません)', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

REVOKE ALL ON FUNCTION reject_audit_log_modification() FROM PUBLIC;

CREATE TRIGGER audit_log_reject_update_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_audit_log_modification();

CREATE TRIGGER audit_log_reject_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_log_modification();
