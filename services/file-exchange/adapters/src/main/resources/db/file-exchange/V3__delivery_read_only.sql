-- 取引先への配送は b2b-gateway に移した(P12 ⑦b。ADR-0032 §8・ADR-0040 §7)。file-exchange はもう配送の記録を書かない。
-- 表は過去の配送の記録として、読み取り専用で残す(消さない)。アプリのロールからは書き込みの権限を外す(承認の条件 3)
REVOKE INSERT, UPDATE ON exchange.delivery FROM "${appRole}";

COMMENT ON TABLE exchange.delivery IS '取引先への配送の過去の記録(P12 ⑦b より前。読み取り専用。以降の送信の記録は b2b-gateway の b2b.file_delivery)。ADR-0032 §8';
