# tests/e2e — E2E のシナリオ

起動したローカル基盤に、公開されたエンドポイント(Gateway・Keycloak・Kafka・S3・Tempo・Prometheus・Grafana)と運用者のコマンド
(`make batch-etl` / `make file-exchange` と同じ compose の run)だけで接続して、ROADMAP の各フェーズの DoD を確かめる。
サービスのコードには依存しない(ブラックボックス)。

```bash
make up PROFILE="order cdc saga batch file saas" E2E=1   # E2E 用の設定(e2e.env。待ち時間を短くする)を足して起動する
make e2e                                            # ./gradlew :tests:e2e:e2eTest(秘密情報は infra/local/.env から渡す)
```

- `E2E=1` は `tests/e2e/e2e.env` を docker compose の `--env-file` に足す。判定の仕組みは変えず、待ち時間(到着の確認の間隔・
  Circuit Breaker の Open の期間・ステップのリトライの間隔)だけを短くする。値と理由は e2e.env のコメント。
- 障害の注入は、コンテナの停止・kill と、表のロック(外部からの操作)で行う。
- DB を読むのは結果の観察だけにし、DB を書き換えて振る舞いを起こすことには使わない。
- CI は `.github/workflows/ci.yml` の `e2e` ジョブ(同じ手順。メモリの使用量は `make stats` を Step Summary に出す)。
- SaaS 連携(`SaasE2E`。P10)は、saas-mock の管理の API(SaaS の画面の代わり。`SAAS_MOCK_ADMIN_TOKEN`)で顧客を作る・変える・
  Webhook を送り直す・Rate Limit を変える。リプレイの確認では、送り手の署名の鍵(`infra/local/secrets/crm-webhook/sender-current`)を読んで
  古い時刻の署名を作る。`E2E=1` のときだけ、integration-flow は `tests/e2e/flows` の Flow を追加で読む(Flow の定義を足すだけで
  新しいマッピングを追加できることの確認。本番の Flow は contracts/flows に置く)。
