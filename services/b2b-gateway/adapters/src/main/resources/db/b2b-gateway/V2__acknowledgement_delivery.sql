-- 応答の送信(P12 ④b2。ADR-0040 §7)。取引先の側の正式名と、送る前に取引先の側にすでにあったか(前の送信が記録の前に落ちた)
ALTER TABLE b2b.acknowledgement
    ADD COLUMN remote_name     text,
    ADD COLUMN already_present boolean NOT NULL DEFAULT false,
    ADD CHECK ((state = 'SENT') = (remote_name IS NOT NULL));
