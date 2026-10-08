-- DBA の作業: Log-based CDC(Debezium)のための設定(ADR-0026)。レガシーのアプリと表の定義(列・型・制約)は変えない。
-- レガシーの側で変えてよいのは、このファイルの範囲だけとする。

-- 削除と更新の変更に、変更前の全部の列を載せる。Debezium のキー(message.key.columns の受注番号)を削除の変更からも作るため。
-- 既定(主キーだけ)では、削除の変更に受注番号が載らない。トレードオフ: WAL の量が増える
ALTER TABLE t_juchu REPLICA IDENTITY FULL;
GRANT SELECT ON t_juchu TO ${cdcRole};

-- Incremental Snapshot の signal 表。レガシーのスキーマ(public)に置かず、CDC 用のスキーマに分ける。
-- Debezium は Snapshot の窓の印(watermark)を書いて消すため、INSERT と DELETE(条件の列を読むため SELECT も)を付ける
CREATE SCHEMA eiaf_cdc;
CREATE TABLE eiaf_cdc.debezium_signal (
    id   VARCHAR(42)   NOT NULL PRIMARY KEY,
    type VARCHAR(32)   NOT NULL,
    data VARCHAR(2048)
);
GRANT USAGE ON SCHEMA eiaf_cdc TO ${cdcRole};
GRANT SELECT, INSERT, DELETE ON eiaf_cdc.debezium_signal TO ${cdcRole};

-- 公開する表を明示する(コネクタの publication.autocreate.mode=disabled)。signal 表も変更を読むために含める
CREATE PUBLICATION eiaf_legacy FOR TABLE t_juchu, eiaf_cdc.debezium_signal;
