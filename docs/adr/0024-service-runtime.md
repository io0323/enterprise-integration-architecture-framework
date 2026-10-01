# ADR-0024: サービスの実行時の構成(エンジン・コマンドと資格情報・予算と打ち切り・ヘルスチェック・設定・mTLS・イメージ)
- Status: Accepted
- Date: 2026-10-01
- Framework 参照章: 5.2, 12.1, 12.2, 12.3, 13.1, 14
- 関連: ADR-0021 §12(呼び出し元の締め切り)、ADR-0022 §3(冪等)、ADR-0017(監査のマイグレーション)、ADR-0018(可観測性)、ADR-0019(JWT)、ADR-0008(mTLS の範囲と開発用 CA)、ADR-0016(イメージの固定)、ADR-0023(⑤c のゲートウェイ)
- 番号: P05 で予約した番号(0022 platform/api / 0023 gateway / 0024 サービスの実行時の構成)

## Context
P05 ④b-2 で、最初のサービス(order-service)を起動できる形にする。後続のサービス(inventory・payment・shipping など)も同じ形にしたいので、次を決める。

1. HTTP サーバのエンジン
2. マイグレーションの資格情報(DB の所有者)を、リクエストを処理するプロセスに持たせるか
3. リクエストの予算と、その打ち切り(コルーチンと DB)、ほかのタイムアウトとの大小関係
4. ヘルスチェック、期限切れの冪等の記録の削除
5. 設定(環境変数)と秘密情報の名前
6. API の mTLS(ゲートウェイ → サービス。ADR-0008)と、ヘルスチェックのポート(P05 ⑤b で追加)
7. コンテナイメージ(P05 ⑤b で追加)

## Decision
### 1. エンジンは Ktor の Netty
- ⑤ の mTLS(APISIX → サービス。ADR-0008)で、TLS のコネクタとクライアント証明書の検証が要る。Ktor の CIO の Server は TLS を扱えないため、最初から Netty にする(⑤ ではコネクタを足すだけにする)。

### 2. コマンドを migrate と serve に分け、所有者の資格情報は migrate にだけ渡す
- 1 つの実行ファイルに 2 つのサブコマンドを持たせる。
  - `migrate`: DB の所有者(`{service}`)の資格情報(`ORDER_DB_PASSWORD`)で、サービスの表と監査の表(ADR-0017)をマイグレーションして終わる。
  - `serve`: アプリのロール(`{service}_app`)の資格情報(`ORDER_APP_DB_PASSWORD`)だけで、リクエストを処理する。**マイグレーションはしない。**
- **serve の環境に所有者のパスワード(`ORDER_DB_PASSWORD` か `ORDER_DB_PASSWORD_FILE`)があれば、起動しない**(終了コード 2)。誤って渡しても、リクエストを処理するプロセスが所有者の権限(DDL・権限の変更・監査のトリガーの無効化)を持たないことを、起動時の検査で保証する。
- 順序: ローカル基盤と統合テストでは、`migrate` を実行してから `serve` を起動する。compose(profile `order`)では、`order-migrate` を 1 回だけ動くコンテナにし、`order-service`(serve)は `depends_on: condition: service_completed_successfully` で待たせ、`serve` の環境には `ORDER_APP_DB_PASSWORD` だけを渡す。`make up` の `--wait` は、終了コード 0 で終わった `order-migrate` を失敗と扱わない(Compose v5.5.1 で確かめた。CI の infra ジョブ `verify (order)` でも確かめる)。
- 理由: 起動のたびに所有者の接続でマイグレーションすると、リクエストを処理するプロセスが、常に所有者のパスワードを持つことになる。プロセスが侵害されたときに、表の削除や監査のトリガーの無効化(ADR-0017 §8 の残るリスク)まで許してしまう(Framework 12.3 の最小権限)。

### 3. リクエストの予算と打ち切り
- 入口(`platform/api` の `installRequestDeadline`)で、リクエストごとに予算(既定 10 秒)を決める。
  - `withCallDeadline(予算)`: 入れ子の `Resilience`(ADR-0021 §12)と DB の打ち切りに、残り時間を引き継ぐ。
  - `withTimeoutOrNull(予算)`: 処理全体を打ち切り、503 `deadline-exceeded` を返す(次の「予算切れの応答」)。`withCallDeadline` は締め切りを伝えるだけで、打ち切らないため。
- **DB 側の打ち切り**: `withTimeout` はコルーチンを打ち切るだけで、待ち状態の JDBC の問い合わせは止まらない。そこで、新しいトランザクションの最初に、`SET LOCAL statement_timeout` に「残り時間 + 余裕(0.5 秒)」を設定する(adapters の `newTransaction`)。
  - 余裕を足すのは、コルーチンの打ち切りを先に起こし、DB の打ち切りがそれを追う順にするため(応答は 503 `deadline-exceeded` になる)。DB の問い合わせは、遅くとも予算 + 余裕で止まる。
  - 呼び出し元のトランザクションに参加する処理は、外側のトランザクションの設定を引き継ぐ。
  - 打ち切られた後の後始末(冪等の処理中の記録の取り消し)には、予算を DB に設定しない。
- **打ち切られた後に確定しない**: JDBC の呼び出しから戻った時点と、トランザクションを確定する直前に、コルーチンが打ち切られていないかを確かめる(`ensureActive()`)。打ち切られていれば、例外でトランザクションを取り消す。冪等の処理(ADR-0022 §3)は、例外のときに処理中の記録を取り消すので、同じキーで再試行すれば、二重にならずに 1 回だけ処理される。

**時間の大小関係**:
| 時間 | 既定 | 決める場所 | 関係 | 理由 |
|---|---|---|---|---|
| リクエストの予算 | 10 秒 | `ORDER_REQUEST_BUDGET`(`installRequestDeadline`) | 冪等のリースより短い。設定の検証で強制する | 予算を超えた処理は打ち切られるので、リースが切れる前に処理中の記録は取り消されるか完了する。リースのほうが短いと、まだ処理中の要求を、同じキーの再送が引き継いで二重に処理しかける(フェンシングで確定は防げるが、無駄な処理になる) |
| DB の文の上限 | 残り時間 + 0.5 秒 | `SET LOCAL statement_timeout` | 予算の後に来る | コルーチンの打ち切りを先にして、応答を 503 `deadline-exceeded` にそろえる |
| 冪等のリース | 60 秒 | `ORDER_IDEMPOTENCY_LEASE`(`IdempotencyConfig.lease`) | 予算より長い | 上のとおり |
| ゲートウェイ(APISIX)の上流のタイムアウト | connect 2 秒・send / read 15 秒 | `infra/local/apisix/apisix.yaml` のルート(ADR-0023 §1) | 予算より長い | サービスが Problem Details(予算切れの 503 など)で応答する前に、ゲートウェイが接続を切らないようにする。ゲートウェイが先に切ると、サービスの処理は続くのに、クライアントには理由の分からない 504 が返る |

大小関係: **リクエストの予算(10 秒)< ゲートウェイの上流のタイムアウト(15 秒)、かつ リクエストの予算 < 冪等のリース(60 秒)**。

**予算切れの応答は 503 `deadline-exceeded`(Problem Details、`Retry-After: 1`)にする**(P05 ⑤a で決めた)。
| 観点 | 503 + `Retry-After` | 504 |
|---|---|---|
| RFC 9110 の意味 | 一時的に処理できない(§15.6.4) | ゲートウェイやプロキシとして、上流から時間内に応答を受け取れなかった(§15.6.5)。サービス自身はゲートウェイではない |
| 結果が確定したか | 上のとおり、打ち切った処理は確定しない。「確定していないので、同じ `Idempotency-Key` で再試行してよい」と伝えられる | ゲートウェイの上流のタイムアウト(⑤。結果は分からない)と同じ番号になり、クライアントが区別できない |
| 再試行の間隔 | `Retry-After` で伝えられる。契約の `ServiceUnavailable` は `Retry-After` をすでに持つ | 標準では `Retry-After` を付けない |
| ほかの慣例 | — | gRPC の `DEADLINE_EXCEEDED` は HTTP の 504 に対応づけられる(504 を選ぶ論拠) |

- クライアントにとっての意味を分ける: **503 `deadline-exceeded` は「確定していない」、ゲートウェイの 504 は「分からない」**。どちらも同じ `Idempotency-Key` で再試行すれば、二重にはならない(ADR-0022 §3)。
- 応答は `platform/api` の `installRequestDeadline` が返す。打ち切るのは予算の期限切れだけで、処理の中の別の `withTimeout` の期限切れは、これまでどおり例外として伝わる(Ktor の 504)。
- 5xx なので冪等の記録には保存しない(ADR-0022 §3)。`ServerObservability` には `markTimedOut()` で伝え、ほかの 503 と区別して `error.type=timeout` で数える(ADR-0018 §5)。

### 4. ヘルスチェックと削除のジョブ
- `/health/live`(プロセスが動いている)と `/health/ready`(DB に接続できる。できなければ 503)。認証しない。平文のヘルスチェックのポートだけで返す(§6)。
- 期限切れの冪等の記録の削除(`IdempotencyStore.purgeExpired`)を、`serve` の中で既定 5 分ごとに行う(`ORDER_IDEMPOTENCY_PURGE_INTERVAL`)。処理中の記録の猶予はリースの長さ(ADR-0022 §3)。失敗しても次の周期でやり直し、サーバの停止で止まる。

### 5. 設定と秘密情報
- 設定は環境変数で渡す。秘密情報は `SecretProvider`(`EnvSecretProvider`。値か `_FILE` の Docker secrets。ADR-0019 §6)から読む。order-service の変数は `OrderConfig` の KDoc に一覧がある。
- 配線は Koin。依存先ごとの `Resilience` は `ResilienceMetrics` 経由で作る(Konsist の `resilienceOnlyThroughMetrics`)。
- JWT の検証は `requireClientId = true`(ADR-0019・ADR-0022 §3)。

### 6. API は mTLS だけで受け、ヘルスチェックは平文のポートに分ける(P05 ⑤b)
- コネクタを 2 つにする。
  | ポート | 既定 | 受けるもの | 公開 |
  |---|---|---|---|
  | API(`ORDER_HTTPS_PORT`) | 8443 | API のルートだけ。**mTLS だけ**(TLS 1.2 / 1.3) | ゲートウェイ(APISIX)から。ローカルではホストの 127.0.0.1:19443 にも出す(mTLS なしの接続を拒否することの検査用) |
  | ヘルスチェック(`ORDER_HEALTH_PORT`) | 8081 | `/health/live`・`/health/ready` だけ。平文 | コンテナの外には出さない(コンテナのヘルスチェック用) |
- ルートは、ポートの番号ではなく接続の種類(Ktor の `request.local.scheme` が `https` か `http` か)で分ける。テストでは空いているポートを使うため、番号が起動するまで決まらない。API のルートは平文のポートでは 404、ヘルスチェックは API のポートでは 404。
- **クライアント証明書は必須**にする(Ktor の `sslConnector` に trustStore を設定すると `needClientAuth` になる)。開発用 CA の署名と期限を確かめる。
- **さらに SAN の DNS 名を許可の一覧(`ORDER_TLS_ALLOWED_CLIENTS`。既定 `apisix`)で確かめる**。同じ CA がほかのサービスの証明書も署名するため、CA の検証だけでは、ゲートウェイ以外のクライアントも API のポートに接続できてしまう。Ktor は TrustManager を差し替えられないので、Netty のパイプラインで TLS の処理(`ssl`)の直後に確認の処理を置き、許可されていなければ HTTP の処理に渡す前に接続を閉じる(`ClientCertificateAllowList`)。このためゲートウェイとの間は HTTP/1.1 にする(`enableHttp2 = false`)。
- 鍵と証明書は PEM のファイル(鍵は PKCS#8。EC P-256)から、メモリ上の KeyStore を作る。キーストアのパスワードという秘密情報を増やさないため(パスワードはプロセスの中で乱数から作る)。
- **起動時に有効期限を確かめる**。サーバ証明書か CA が期限切れ(またはまだ有効でない)なら起動しない。エラーには、どの設定の証明書か、`notAfter`、直し方(`make certs`。docs/runbooks/dev-certificates.md)を書く。残りが 7 日を切っていれば WARN を残す。
- 開発用の CA と証明書は `infra/local/scripts/gen-dev-certs.sh` が `infra/local/certs/`(.gitignore 済み)に作る(ADR-0008)。CA は 90 日、証明書は 30 日。`make up` と `make certs` のたびに確かめ、**残りが 7 日を切っている・今の CA で検証できない・ない証明書だけを作り直す**(CA を作り直したらすべて)。起動中のコンテナは証明書を起動時にしか読まないので、作り直したら `make up` が証明書を使うコンテナを作り直す(Makefile の `CERT_CONSUMERS`)。

### 7. コンテナイメージ(P05 ⑤b)
- ベースは distroless の Java 21 の nonroot(`gcr.io/distroless/java21-debian12:nonroot`)。images.env の `JAVA_RUNTIME_IMAGE` にタグとダイジェストで固定する(ADR-0016 §2)。シェルとパッケージマネージャがなく、攻撃面が小さい。uid 65532 で動く。
- シェルがないので、Gradle の起動スクリプトは使わず、`java -cp '/app/lib/*' io.eia.order.app.MainKt` で起動する。中身は `installDist` の出力で、`make up PROFILE=order` が先に作る(`make order-dist`)。
- ヘルスチェックは、ほかのシェルのないイメージと同じく、BusyBox の wget を `/probe` にマウントして使う(ADR-0016 §4)。
- **root で動かさない**。
  - **ローカルでは、ホストの利用者の uid で動かして 0600 の鍵を読む**(compose の `user` に Makefile の `EIAF_UID` / `EIAF_GID` を渡す)。鍵(`infra/local/certs/*.key`)は、`make certs` を実行した利用者だけが読める。
  - ホストの uid が 0(root)なら、`make up` と `make certs` は起動を止めて理由を表示する。root で実行すると、コンテナも root で動き、distroless の nonroot の意味がなくなるため。
  - **本番では、イメージの固定の nonroot の uid(65532)で動かし、鍵の読み取り権限は Secret の配置で与える**(例: Secret を読み取り専用でマウントし、ファイルのグループをコンテナのグループにして 0440 にする。Kubernetes なら `fsGroup` と Secret の `defaultMode`)。ホストの uid に合わせる形は使わない。

## Alternatives Considered
- **Ktor の CIO**: 依存は軽いが、Server は TLS を扱えず、⑤ でエンジンを替えることになる。不採用。
- **起動時に所有者の接続でマイグレーションする**: 起動の手順は 1 つで済むが、§2 のとおり、リクエストを処理するプロセスが所有者のパスワードを持つ。不採用。
- **マイグレーションを別のツール(Flyway の CLI のコンテナなど)にする**: サービスの実行ファイルとマイグレーションの版がずれうる。監査の表(`AuditSchema`)も同じ手順で適用したいので、サービスのサブコマンドにした。不採用。
- **`withTimeout` だけで打ち切る**: コルーチンは止まるが、JDBC の問い合わせは DB で続き、接続も戻らない。打ち切られた後に確定が起きる余地も残る。不採用。
- **DB 側の打ち切りだけにする(`withTimeout` を使わない)**: DB を使わない処理(下流の呼び出しなど)が打ち切られない。不採用。
- **予算切れを 504 にする**: §3 の表のとおり、ゲートウェイの 504 と区別できず、`Retry-After` も付けられない。不採用。
- **statement_timeout を残り時間ちょうどにする**: DB の打ち切り(SQL の例外)とコルーチンの打ち切りが競い、応答の種類(`service-unavailable` と `deadline-exceeded`)が定まらない。余裕を足して、コルーチンを先にした。不採用。

- **TLS を任意にする(証明書がなければ平文で API を受ける)**: 設定の漏れで、mTLS のない API のポートができてしまう。serve では TLS のファイルを必須にした。不採用。
- **ポートの番号でルートを分ける(Ktor の `localPort`)**: 空いているポートを使うテストで、番号が起動するまで決まらない。接続の種類で分けた。不採用。
- **SAN を確かめず、CA の検証だけにする**: 同じ CA が署名したほかのサービスの証明書でも、API のポートに直接接続できる(ゲートウェイの JWT の検証や Rate Limit を迂回できる)。不採用。
- **キーストア(PKCS#12)のファイルを渡す**: キーストアのパスワードという秘密情報が増え、APISIX(PEM を使う)と形式が分かれる。不採用。
- **eclipse-temurin の JRE に root 以外の利用者を加える**: シェルとパッケージがあり攻撃面が大きい。不採用(P05 ⑤ の計画で決めた)。
- **鍵を 0644 にして、イメージの既定の uid 65532 で動かす**: 同じホストのほかの利用者が鍵を読める。ホストの利用者の uid で動かす形にした。不採用。
- **ホストで root として実行した場合は、65532 で動かし、鍵の所有者を 65532 に変える**: ⑤b ではこの形にしたが、root で運用する手順を残すことになり、鍵の所有者がホストの利用者と食い違う。root では止める形に変えた(P05 ⑤c)。不採用。

## Consequences(トレードオフ)
- デプロイの手順が 2 段階(migrate → serve)になる。compose(§2)と、本番の配備の仕組み(Job・init container など)で順序を守る必要がある。
- `./gradlew :services:order:app:run` などでホストから serve を動かすときも、TLS のファイル(`ORDER_TLS_*`)が要る(`make certs` が作る `infra/local/certs/` を指す)。
- ゲートウェイとの間は HTTP/1.1 になる(§6 の許可の一覧の確認のため)。HTTP/2 にするときは、確認の処理を HTTP/2 のパイプラインにも置く必要がある。
- distroless のタグ `nonroot` は上流が付け直す(OS のパッケージの更新など)。固定したダイジェストの更新は、Issue #35 の定期確認(images.env の全イメージが対象)で検知する。
- DB の問い合わせは、予算 + 0.5 秒まで続きうる(その間、接続は使われたまま)。
- 予算を超えた要求は 503 `deadline-exceeded` になり、同じキーで再試行すると処理し直す(打ち切られた処理は確定していない)。
- ゲートウェイの上流のタイムアウトの 504(結果は分からない)は、契約(`GatewayTimeout`)に加え、Problem Details(`about:blank`)で返す(ADR-0023 §6)。

## 改訂履歴
- 2026-10-01: 作成(P05 ④b-2)。
- 2026-10-01: P05 ⑤a で、予算切れの応答を Ktor の既定の 504 から 503 `deadline-exceeded`(Problem Details、`Retry-After`)に変えた(§3 の表)。契約(order-api.v1 の `ServiceUnavailable`)と INTEGRATION_STANDARDS §6 にも加えた。
- 2026-10-01: P05 ⑤b で、§6(API は mTLS だけで受け、SAN の許可の一覧を確かめる。ヘルスチェックは平文のポート。証明書の有効期限の確認と、7 日を切ったときの作り直し)と §7(distroless の nonroot のイメージ)を加えた。compose の `order-migrate` → `order-service` の順序を実装した(§2)。
- 2026-10-01: P05 ⑤c で、§7 を直した。ホストの uid が 0(root)なら `make up` / `make certs` を止める(以前は 65532 で動かし、鍵の所有者を変えていた)。本番では固定の nonroot の uid で動かし、鍵の読み取り権限は Secret の配置で与えることを書いた。
- 2026-10-01: P05 ⑤c で、ゲートウェイの上流のタイムアウトを 15 秒にした(§3 の表。ADR-0023 §1)。
