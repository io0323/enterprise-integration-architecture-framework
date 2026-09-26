# ADR-0008: ローカル参照実装で設計書から縮退する範囲
- Status: Accepted
- Date: 2026-09-25
- Framework 参照章: 7.2, 11.1, 12.1, 12.2, 14.1, 17.2

## Context
本リポジトリは Docker Compose だけで完結するローカル参照実装であり(ADR-0003)、Framework の一部の要件は、そのままでは実装コストやローカル負荷が見合わない。
CLAUDE.md §6 の「設計書と乖離する場合は ADR を書く」に従い、縮退する範囲と、本番で何に置き換えるかを明記する。

## Decision
| 項目 | Framework の要求 | 本実装 | 本番での置き換え |
|---|---|---|---|
| Secrets(12.2) | Vault 型の集中管理と自動ローテーション | `platform/security` に `SecretProvider` Port を定義し、ローカルは環境変数と Docker secrets で実装する | Vault / OpenBao / クラウドの Secret Manager の Adapter を追加する |
| mTLS(12.1) | 内部サービス間・B2B・高機密連携で必須 | `infra/local/certs/` の開発用 CA(スクリプトで生成し、コミットしない)で、**APISIX → サービス**(P05)と **gRPC**(P13)に適用する。Kafka の SSL / ACL は任意の `secure` profile とする | Service Mesh か証明書の自動ローテーション基盤 |
| ワークフロー(7.2) | DAG オーケストレータ、完了起動、SLA の遅延予測 | `platform/batch` に軽量 DAG ランナーを作る: DAG 定義(Kotlin DSL)、前段の完了イベント・ファイル到着での起動、Checkpoint、SLA 遅延予測のメトリクス | 汎用のワークフロー基盤(ADR で選定) |
| Audit(14.1) | 改竄不能なストレージに記録 | `platform/audit`: PostgreSQL の追記専用テーブル(UPDATE / DELETE 権限を付けない)に、前のレコードのハッシュを含めるハッシュチェーンで記録する。日次でチェーンの先頭ハッシュを S3 互換ストレージ(SeaweedFS。ADR-0015)の Object Lock(Compliance モード)に保存する | WORM ストレージや監査専用基盤 |
| B2B Protocol(11.1) | AS2(MDN)を既定、SFTP、OFTP2 | SFTP のみ。受領応答は CONTRL 相当のドキュメントで返す | AS2 / OFTP2 対応の B2B Gateway |
| 保存データの暗号化(12.2) | Broker・キュー・ステージング・ファイル保管の暗号化、フィールドレベル暗号化 | **範囲外**。ファイルの PGP 暗号化(P09)とマスキングユーティリティのみ実装する | 各基盤の暗号化機能と KMS |
| Service Mesh(17.2) | 内部同期は gRPC + Service Mesh(mTLS) | **範囲外**。mTLS は上記の範囲で直接設定する | Service Mesh |
| Event Mesh(4.1) | 複数 Broker・リージョンの接続 | **範囲外**。単一の Kafka クラスタ | 必要時に ADR |
| 転送路の暗号化(9, 12.2) | 全連携で TLS1.2+(平文禁止) | **ローカルは平文**: Kafka(PLAINTEXT。SASL_SSL は #26)、MQTT(1883)、S3(SeaweedFS の http)、Keycloak(`sslRequired: none`)、APISIX(http)。SFTP は SSH で暗号化される。すべて 127.0.0.1 にだけ公開する(ADR-0016 §6)。TLS 化は #29 | 各基盤の TLS と証明書の自動ローテーション |
| 基盤の管理 API の認証(12.3) | 境界内でも認証必須 | **ローカルは未認証**: Apicurio の REST、Kafka Connect の REST、Toxiproxy の API、Prometheus の OTLP / remote write 受信、Loki、Tempo、OTel Collector の OTLP 受信。127.0.0.1 にだけ公開する。認証の追加は #29 | 各製品の認証(Apicurio は OIDC、Connect の REST は Basic 認証か mTLS など)とネットワーク分離 |

## Alternatives Considered
- Vault(または OpenBao)をコンテナで追加する: ローカル負荷と起動手順が増える割に、Port 経由で差し替えられることは示せる。範囲を限定するために不採用。
- 汎用のワークフロー基盤(Airflow・Dagster・Temporal など)を追加する: Framework の要件(DAG・完了起動・SLA 予測)は実証できるが、Python 系ランタイムや別の DB が増え、Kotlin 単一スタックを崩す。不採用。
- Audit を Loki のログで代用する: 改竄不能とは言えないため不採用。

## Consequences(トレードオフ)
- 範囲外とした項目は、本リポジトリを採用しても満たされない。README とカタログの NFR 計測レポート(`docs/reports/`)に明記する。
- `platform/batch` と `platform/audit` の 2 モジュールが増える。
- 開発用 CA の鍵はコミットしない(P05 で `infra/local/certs/` を .gitignore に追加する。`**/secrets/` は既に対象)。

## 改訂履歴
- 2026-09-26: Audit の日次アンカーの保存先を MinIO から SeaweedFS に読み替えた(ADR-0015)。Kafka の SSL / ACL の `secure` profile は P03 の範囲から外し、Issue #26 で扱う(ADR-0016 §8)。
- 2026-09-26: 転送路の暗号化と基盤の管理 API の認証の縮退を表に追加した(PR #28 のレビュー。対応は #29)。
