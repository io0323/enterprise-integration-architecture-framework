# tests/e2e — E2E のシナリオ

起動したローカル基盤に、公開されたエンドポイント(Gateway・Keycloak・Kafka・S3・Tempo・Prometheus・Grafana)と運用者のコマンド
(`make batch-etl` / `make file-exchange` と同じ compose の run)だけで接続して、ROADMAP の各フェーズの DoD を確かめる。
サービスのコードには依存しない(ブラックボックス)。

```bash
make up PROFILE="order cdc saga batch file" E2E=1   # E2E 用の設定(e2e.env。待ち時間を短くする)を足して起動する
make e2e                                            # ./gradlew :tests:e2e:e2eTest(秘密情報は infra/local/.env から渡す)
```

- `E2E=1` は `tests/e2e/e2e.env` を docker compose の `--env-file` に足す。判定の仕組みは変えず、待ち時間(到着の確認の間隔・
  Circuit Breaker の Open の期間・ステップのリトライの間隔)だけを短くする。値と理由は e2e.env のコメント。
- 障害の注入は、コンテナの停止・kill と、表のロック(外部からの操作)で行う。
- DB を読むのは結果の観察だけにし、DB を書き換えて振る舞いを起こすことには使わない。
- CI は `.github/workflows/ci.yml` の `e2e` ジョブ(同じ手順。メモリの使用量は `make stats` を Step Summary に出す)。
