# tests/e2e — E2E のシナリオ

起動したローカル基盤に、公開されたエンドポイント(Gateway・Keycloak・Kafka・S3・Tempo・Prometheus・Grafana)と運用者のコマンド
(`make batch-etl` / `make file-exchange` と同じ compose の run)だけで接続して、ROADMAP の各フェーズの DoD を確かめる。
サービスのコードには依存しない(ブラックボックス)。

```bash
make up PROFILE="order cdc saga batch file b2b saas iot bff" E2E=1   # E2E 用の設定(e2e.env。待ち時間を短くする)を足して起動する
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
- IoT のテレメトリ(`IotE2E`。P11)は、native のデバイスのシミュレータ(`tools/device-simulator`。この OS の release の実行ファイル。
  CI は linuxX64、手元の Mac は macosArm64)をデバイスとして動かす。デバイスのパスワードは `make env` が作る
  `infra/local/secrets/mqtt/device-passwords` から読み、環境変数でシミュレータに渡す。シミュレータではできない操作(他のデバイスの
  トピックへの書き込み・同じメッセージの再送)は、同じ SDK の MQTT の部分(`shared/integration-sdk/mqtt`)で送る。
- GraphQL BFF(`BffE2E`。P13)は、Gateway 経由のクエリの結果と、在庫・出荷のコンテナの停止(部分応答)で確かめる。mTLS の拒否は gRPC のポートに直接つないで確かめる。
- B2B / EDI(`B2bGatewayE2E`。P12)は、取引先 partner01 の側として、GnuPG(`make pgp-keys` と同じイメージ `eiaf-gnupg:local`)で
  署名・暗号化した受注の文書を、ホストの `sftp`(`infra/local/secrets/sftp-b2b` の鍵。ホスト鍵は known_hosts に固定)で sftp-b2b に置き、
  届いた応答(CONTRL・APERAK)を取り出して復号・署名の検証をする。受け付け(order の API に取り込んで肯定の APERAK)・業務・スキーマ・構文・
  復号のそれぞれの拒否で、応答・運用の通知(`B2bDocumentRejected`)・監査を確かめ、最後に `make audit-verify SERVICE=b2b-gateway` を通す。
  受け付けのために、運用者のコマンド(`make b2b-codes` と同じ)で partner01 の対応表(buyers・items)を **E2E 専用の値で丸ごと入れ替える**。
  値は `make verify PROFILE=b2b` が使う買い手・商品と別なので、E2E の後に verify を動かしても結果は変わらない。
