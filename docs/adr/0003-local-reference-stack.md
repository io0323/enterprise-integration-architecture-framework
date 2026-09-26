# ADR-0003: ローカル参照実装のミドルウェア選定
- Status: Accepted
- Date: 2026-09-25
- Framework 参照章: 1.9, 17

## Decision
OSS かつ標準プロトコル準拠のもので構成する(いずれも交換可能であることを前提):
Kafka(KRaft) / Debezium / Apicurio Registry / PostgreSQL / Keycloak / Apache APISIX / Mosquitto / S3 互換ストレージ(SeaweedFS。ADR-0015) / SFTP /
OpenTelemetry Collector / Prometheus / Grafana / Tempo / Loki / Toxiproxy
## Rationale
Cloud Agnostic。各製品は Kafka プロトコル・S3 API・OIDC・OTLP 等の標準 I/F で利用し、製品固有機能に依存しない。
## Consequences
ローカル負荷が大きいため docker compose profiles で分割起動する。

## 改訂履歴
- 2026-09-26: MinIO(上流がアーカイブされ、公開イメージも取得できない)を SeaweedFS に置き換えた(ADR-0015)。Compose の構成・版の固定・profile は ADR-0016。
