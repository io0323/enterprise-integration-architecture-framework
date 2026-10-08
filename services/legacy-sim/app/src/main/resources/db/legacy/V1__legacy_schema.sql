-- 既存のレガシー基幹の受注表(模擬。改修できない前提で、この定義は変えない。ADR-0026)。
-- レガシーらしさ: 意味のない列名、固定長の文字列(CHAR は末尾を空白で埋める)、コード値の状態、通貨のない小数の金額、
-- タイムゾーンのない現地時刻(JST)、「未設定」を表す日付の番兵値。
-- 業務の主キーは受注番号(COL_02)だが、主キーは採番の連番(COL_01)。
CREATE SEQUENCE sq_juchu;

CREATE TABLE t_juchu (
    col_01 NUMERIC(10)                    NOT NULL PRIMARY KEY,              -- 受注 SEQ(採番)
    col_02 CHAR(10)                       NOT NULL UNIQUE,                   -- 受注番号(例 J000000001)
    col_03 CHAR(1)                        NOT NULL,                          -- 状態区分: 1 受付 / 2 引当済 / 3 出荷済 / 9 取消
    col_04 CHAR(40)                       NOT NULL,                          -- 顧客名
    col_05 CHAR(8)                        NOT NULL,                          -- 顧客コード
    col_06 NUMERIC(13, 2)                 NOT NULL,                          -- 受注金額(円。小数 2 桁の列だが、円に小数はない)
    col_07 TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,                          -- 受注日時(JST の現地時刻)
    col_08 TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL DEFAULT '9999-12-31 00:00:00' -- 最終更新日時(JST。9999-12-31 は未設定)
);

-- レガシーのアプリのロール。表を所有しない
GRANT SELECT, INSERT, UPDATE, DELETE ON t_juchu TO ${appRole};
GRANT USAGE ON SEQUENCE sq_juchu TO ${appRole};
