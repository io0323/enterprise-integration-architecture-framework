# ADR-0042: 社内の照会の gRPC(契約・接続・締め切り・状態コード・認証)
- Status: Accepted
- Date: 2026-11-02
- Framework 参照章: 3.1, 5.1, 5.3, 12.1, 13, 14, 15
- 関連: ADR-0008(mTLS の範囲)、ADR-0013(contract-check。ツールの固定)、ADR-0021(Resilience・CallDeadline)、ADR-0023(ゼロトラストの考え方)、ADR-0024(リクエストの予算)
- 番号: P13 の計画で決めた番号(0042 gRPC / 0043 GraphQL BFF)

## Context
P13(#16)で、BFF が画面のために注文・在庫・出荷を集約する(ADR-0043)。inventory と shipping には、これまで同期の読み取り口がなかった(Kafka のコマンドと、平文のヘルスチェックのポートだけ)。Framework 5.1 は、社内の低遅延の通信に gRPC を割り当て、社外には公開しない。

ここでは、契約の範囲と形、置き場所と命名、破壊的変更の検査(P13 ①)と、接続・締め切り・伝搬・状態コードの写し方・証明書のエラー・認証(P13 ②)を決める。実装は `platform/grpc` と `platform/grpc-stubs`。

## Decision
### 1. 範囲: inventory と shipping の照会
- ROADMAP P13 は inventory の在庫照会だけを挙げているが、**shipping の出荷照会も gRPC で公開する**。BFF の集約には出荷が要り、shipping には読み取り口がないため。ROADMAP を直した。
- どちらも**読み取りだけ**にする。在庫と出荷を変えるのは、これまでどおり注文 Saga のコマンド(ADR-0029)だけ。
- 注文は gRPC にしない。BFF から Gateway 経由の REST で読む(ADR-0043)。同じ読み取りの契約を 2 つ持たないため。

### 2. 置き場所と命名
| 対象 | 規則 | 例 |
|---|---|---|
| ファイル | `contracts/proto/io/eia/{domain}/v{n}/{name}.proto` | `contracts/proto/io/eia/inventory/v1/stock_query_service.proto` |
| package | `io.eia.{domain}.v{n}`(版を含める) | `io.eia.inventory.v1` |
| `java_package` | `io.eia.platform.grpcstubs.{domain}.v{n}` | `io.eia.platform.grpcstubs.inventory.v1` |
| カタログ | 1 ファイル 1 連携(`style: grpc`) | INT-INVENTORY-003・INT-SHIPPING-003 |

- `java_package` を package と分けるのは、サービスのパッケージ(`io.eia.inventory`)のままだと、BFF が生成したスタブを import したときに、サービスの分離の Konsist(`serviceIsolation`)に反するため。スタブは 1 か所(`platform/grpc-stubs`。P13 ②)で生成して共有する。
- proto は外部のレジストリの依存を持たない。import してよいのは Protobuf の標準の型(`google/protobuf/*`)だけ(buf に同梱で、ネットワークを使わない)。

### 3. 検査は buf(版とチェックサムを固定)
| 候補 | 評価 |
|---|---|
| **buf 1.75.0(採用)** | lint と破壊的変更の検査が 1 つの単一バイナリにそろう。JSON で結果を受け取れる。`deps` を書かなければ外部に接続しない。JVM のプロセスから oasdiff と同じ形で動かせる |
| protoc で記述子を作って自前で比べる | 規則(番号・型・名前・削除・パッケージ・オプション)の網羅を自分で持つことになる。ADR-0013 で oasdiff を選び、規則の自作を退けた理由と同じ。不採用 |
| buf を Docker イメージで動かす | `./gradlew build` のテストで Docker が必須になる(ADR-0013 と同じ理由)。不採用 |

- 取得は oasdiff と同じ扱い(ADR-0013 §3)。版は `libs.versions.toml` の `buf`、プラットフォームごとの SHA-256 は `tools/contract-check/build.gradle.kts` に固定し、一致しなければビルドを失敗させる。
- 規則は `contracts/proto/buf.yaml` に置く。
  | ルール | buf | 内容 |
  |---|---|---|
  | `CC-STRUCT-006` | `buf lint`(STANDARD) | package の版、ディレクトリとの一致、命名、構文 |
  | `CC-COMPAT-006` | `buf breaking`(FILE) | main の版からの破壊的変更。FILE は最も厳しい区分で、ワイヤの互換だけでなく、生成コードの互換(項目名・パッケージの変更)も守る。社内の利用者はスタブで動くため |
  | `CC-NAMING-013` | (contract-check) | 置き場所と `java_package`。buf は `io.eia` の接頭辞と Java のパッケージの決まりを知らない |
- buf の振る舞い(1.75.0 で確かめた): 違反は終了コード 100 で JSON を 1 行に 1 件、ツールの失敗は終了コード 1。比較元に proto がないと失敗するので、そのときは新規として検査しない。proto がすべて消えたときも buf を使えないので、contract-check が消えたファイルごとに違反にする。ファイルの削除の報告には `path` がないので、文から取り出す(互換性の違反の許可は、ファイルの単位で判定するため)。
- 設計中の連携の扱いと例外の扱いは、ほかの契約と同じ(ADR-0013 §6・§7)。

### 4. 形: まとめて読む RPC だけにする
| RPC | 1 回の上限 | 見つからないとき |
|---|---|---|
| `StockQueryService.BatchGetStock` | SKU 500 件 | `unknown_skus` に返す |
| `ShipmentQueryService.BatchGetShipments` | 注文 20 件 | 応答に含めない(まだ手配していないか、保持期間を過ぎた) |

- 1 件ずつ読む RPC は持たない。呼び出し側に N+1 の書き方をさせないため(ADR-0043)。
- **「ない」と「読めなかった」を区別する**。ないことは正常な応答で、読めなかったことは gRPC の状態コードで返す。呼び出し側は、在庫の照会の失敗を「在庫がない」として表示してはいけない。
- 注文 20 件は、注文の API のまとめて読む口の上限にそろえる(ADR-0043)。SKU 500 件は、注文の明細の数に契約の上限がないため、呼び出し側が件数で分割する前提の 1 回の上限にする。
- `available`(引当できる数)は inventory が計算して返す。呼び出し側で計算しない(ADR-0043 の BFF の線引き)。読んだ時点の値で、引当を保証しない。
- 出荷が返すのは国までで、住所を持たない(shipping の記録と同じ。Framework 12.2)。Saga の内部の印(取消済み)は返さない。

### 5. 接続: mTLS だけで受け、チャネルとサーバは `platform/grpc` だけで作る
**ライブラリ**(確認日 2026-11-03)
| ライブラリ | 版 | 理由 |
|---|---|---|
| grpc-java(`grpc-netty-shaded`) | 1.85.1 | Netty を内包した版。Ktor の Netty(ADR-0024 §1)と版がぶつからない。`ClientCertificateAllowList` は Netty のパイプラインに置く作りで再利用できないので、SAN の確認はインターセプタで書く(名前の取り出しだけ共有する) |
| grpc-kotlin | 1.5.0 | suspend のスタブ。最新だが 2025-09 の版で、grpc-java 1.62 でビルドされている。1.85.1 との組み合わせを `platform/grpc` のテストで確かめた。動かなくなったら、grpc-java のスタブに薄い suspend の層を被せる |
| protobuf(protoc・protobuf-java) | 4.36.2 | 安定版の最新(4.37 は RC)。grpc-protobuf 1.85.1 の依存は 3.25.9 だが、4.x の実行時でテストが通ることを確かめた |

**サーバ**(`GrpcServer`)
- クライアント証明書は必須(`ClientAuth.REQUIRE`)。平文の接続とクライアント証明書のない接続は、ハンドシェイクで失敗する(ROADMAP P13 の DoD)。ポートは mTLS だけで、ヘルスチェックはこれまでどおり平文の別のポート(ADR-0024 §6)。
- 同じ CA がほかのサービスの証明書も署名するので、**SAN の DNS 名を許可の一覧でも確かめる**(ADR-0024 §6 と同じ)。一覧にない場合は `PERMISSION_DENIED`。
- 鍵と証明書は `ServerTls`(PEM から作る。期限切れなら起動しない)。呼び出し側のチャネルも同じものを使う。
- **サーバのリフレクションは実装しない**。本番で無効にする設定を間違える余地をなくすため。依存(`grpc-services`)も足さない。開発では `grpcurl -proto contracts/proto/...` を使う。
- 上限: 受信するメッセージ 1 MiB、1 接続の同時の呼び出し 100、ハンドシェイク 5 秒。

**チャネル**(`GrpcChannels`)
- **自動のリトライを止める**(`disableRetry`)。再試行は `Resilience` だけが行う。重ねると回数が掛け算になる。
- target の host をサーバ証明書の SAN と照合する。keepalive は 30 秒(サーバの `permitKeepAliveTime` 20 秒より長い)。

Konsist で強制する。
| 規則 | 内容 |
|---|---|
| `grpcOnlyInAllowedModules` | `io.grpc` は `platform/grpc` と `services/*/adapters`・`app` だけ |
| `grpcBuildersOnlyInPlatform` | チャネルとサーバのビルダーは `platform/grpc` だけ(mTLS と `disableRetry` を抜かせない) |
| `grpcReflectionForbidden` | リフレクションはどこでも使わない |
| `LibraryDeclarationRules` | gRPC と protobuf の依存の宣言は `platform/grpc`・`platform/grpc-stubs` だけ |

### 6. 締め切りと伝搬
**呼び出し側**(`GrpcClient`)
- `Resilience` が試行ごとに置く `CallDeadline`(ADR-0021 §12)の残り時間を、gRPC の deadline にする。**50 ミリ秒だけ長くする。**
  - 同時に切れると、`Resilience` の打ち切りと gRPC の `DEADLINE_EXCEEDED` のどちらが先かで数え方が変わる。呼び出し元の締め切りで打ち切った試行は数えないが(ADR-0021 §12)、`DEADLINE_EXCEEDED` は失敗に数えるため。余裕を足して、打ち切りは常に `Resilience` に任せる。gRPC の deadline は、締め切りをサーバに伝える役と、`Resilience` の外で呼ばれたときの保険になる。
- `CallDeadline` がなければ既定の 3 秒を使う。残り時間がなければ送らない(トークンも取らない)。

**サーバ**(`GrpcServerInterceptor`・`grpcResponse`)
- 呼び出し元の deadline と、サーバの上限(既定 3 秒)の短い方を `CallDeadline` で置く。deadline のない呼び出しにも上限を適用する。
- `grpcResponse` が残り時間で打ち切り、`DEADLINE_EXCEEDED` を返す。呼び出し元が切れたときは gRPC の取り消しでコルーチンが止まる。
- 待ち状態の JDBC はコルーチンの打ち切りでは止まらないので、DB は `statement_timeout` で切る(ADR-0024 §3)。inventory の設定は P13 ③ で足した(`newTransaction`)。shipping は P13 ④ で足す。

**伝搬**: `traceparent` は `EiaTraceContextPropagator`(`shared/resilience` の `TraceParent` を使う)で、Correlation ID は `x-correlation-id` で metadata に入れる。受ける側で値がない・不正なら Correlation ID を採番する。社内の mTLS で確かめた相手だけが接続するので、受け取った `traceparent` は信頼する(Gateway が外部の値を捨てるのとは違う。ADR-0023 §4)。

**メトリクス**: `rpc.client.call.duration` と `rpc.server.call.duration`(秒のヒストグラム。属性 `rpc.system.name`・`rpc.method`・`rpc.response.status_code`・`error.type`)。OTel の RPC の semconv に合わせた。要求の内容とトークンは入れない。

### 7. 状態コードの写し方
| 区分 | 状態コード | `DomainError` | 再試行 | Circuit Breaker |
|---|---|---|---|---|
| 一時的 | `UNAVAILABLE`(§8 の証明書を除く)・`DEADLINE_EXCEEDED`・`RESOURCE_EXHAUSTED`・`ABORTED` | `GrpcCallUnavailable`(Retryable) | する | 失敗 |
| トークン | `UNAUTHENTICATED` | 1 回目はトークンを捨てて同じ試行で送り直す。2 回目は `GrpcCallRejected` | 1 回だけ | 成功(応答している) |
| 恒久的 | `INVALID_ARGUMENT`・`NOT_FOUND`・`ALREADY_EXISTS`・`PERMISSION_DENIED`・`FAILED_PRECONDITION`・`OUT_OF_RANGE`・`UNIMPLEMENTED`・`INTERNAL`・`DATA_LOSS`・`UNKNOWN` | `GrpcCallRejected`(NonRetryable) | しない | 成功(応答している) |
| 取り消し | `CANCELLED` | `GrpcCallCancelled`(NonRetryable) | しない | 数えない |
| 証明書 | §8 | `GrpcCertificateFailure`(NonRetryable) | しない | 数えない |

- `INTERNAL` と `UNKNOWN` を再試行しないのは、HTTP の 500 と同じ考え方(ADR-0021 §3)。
- `Status.description` は `DomainError` に入れない。相手が書く文を、ログや上流の応答に流さないため。受ける側の `description` にも、決まった文かエラーコードだけを入れる。
- 受ける側の写し方(`grpcResponse`): `ValidationError` は `INVALID_ARGUMENT`、締め切りの超過は `DEADLINE_EXCEEDED`、Retryable は `UNAVAILABLE`、NonRetryable は `INTERNAL`、想定外の例外はログに残して `INTERNAL`。
- `RESOURCE_EXHAUSTED` の待ち時間の指定(`grpc-retry-pushback-ms`)は読まない。今の照会のサーバは送らない。

### 8. 証明書のエラーは再試行せず、Circuit Breaker に数えず、別に数える
grpc-java は TLS のハンドシェイクの失敗をすべて `UNAVAILABLE` にする。そのまま写すと、証明書の設定の誤りが再試行され、Circuit Breaker を開き、「依存先の障害」に見える。再試行では直らないし、依存先は健全かもしれない。

**判別できることを確かめた**(`GrpcTlsFailureSpec`。grpc-java 1.85.1 の BoringSSL)。状況ごとの例外の連鎖と `reason` は次のとおり。
| 状況 | 例外の連鎖 | `reason` |
|---|---|---|
| サーバ証明書の期限切れ | `CertificateExpiredException` | `expired` |
| サーバ証明書の CA を信頼できない | `CertPathBuilderException`・`ValidatorException` | `untrusted` |
| サーバ証明書の SAN に接続先がない | `CertificateException: No subject alternative ...` | `hostname_mismatch` |
| 自分のクライアント証明書の期限切れ | alert `SSLV3_ALERT_CERTIFICATE_EXPIRED` | `expired` |
| クライアント証明書がない・相手が信頼しない CA のもの | alert `TLSV1_ALERT_CERTIFICATE_REQUIRED` | `rejected_by_peer` |

- **TLS 1.3 でも判別できる**。クライアント証明書の拒否はハンドシェイクの後に届くが、alert の名前が `Status.cause` に残る。
- 証明書と判別したら `GrpcCertificateFailure`(NonRetryable + `UncountedFailure`)にする。再試行せず、Circuit Breaker とリトライバジェットに成功としても失敗としても数えない。Half-Open なら枠を返す(ADR-0021 §3)。NonRetryable は通常「成功」に数えるが、接続できていないので、Half-Open を閉じてしまわないよう数えない。
- **別のメトリクスとアラート**(`infra/local/prometheus/rules/grpc.rules.yml`。テストは `grpc.test.yml`)。
  | メトリクス | 属性 | アラート |
  |---|---|---|
  | `eia.grpc.client.certificate_failures` | `eia.dependency.name`・`reason` | `GrpcClientCertificateFailure`(critical。1 件目で鳴らす) |
  | `eia.grpc.server.certificate_rejections` | `reason`(`expired`・`untrusted`・`client_not_allowed`) | `GrpcClientCertificateRejected`(warning) |
- 受ける側は、クライアント証明書の検証を数える `TrustManager` で包む(判定は変えない)。証明書の中身はログにもメトリクスにも出さない。

**残るリスク**
- alert は文字列で判別する。grpc-java や BoringSSL の更新で名前が変わると、`GrpcTlsFailureSpec` が失敗するので、更新のときに気づける。
- クライアント証明書のない接続と、相手が信頼しない CA の証明書は、受ける側の `TrustManager` まで届かない(相手がクライアント証明書を送らないため)。受ける側のメトリクスには出ず、呼び出し側のメトリクスで見る。
- 平文のクライアント、途中で切れたハンドシェイク、TLS の版や暗号の不一致は、証明書と判別しない。`UNAVAILABLE`(一時的)のままで、再試行され Circuit Breaker に数える。平文の接続は相手から `Network closed for unknown reason` としか見えず、`GrpcChannels` を使えば起きない。

### 9. 認証: mTLS と JWT の両方を確かめる
- **両方を必須にする。** mTLS は「どのサービスが接続しているか」を、JWT は「何をしてよいか」(スコープ)を確かめる。ゼロトラストの考え方(Framework 12.3。ADR-0023 §2)。
  - 許可の一覧だけでは、サービス全体を許すか許さないかしか決められない。トークンなら、RPC の増減に合わせてスコープで分けられ、呼び出し元のクライアント(`azp`)も監査できる。
  - mTLS を入れない選択肢はない(ADR-0008・ROADMAP の DoD)。
- スコープは `GrpcService` の必須の引数にする。スコープのないサービスは公開できない。
- 確認はインターセプタで行う。サービスの実装が呼び忘れられないため。確かめる順は、許可の一覧 → トークン → スコープ。
  | 結果 | 状態コード |
  |---|---|
  | トークンがない・不正 | `UNAUTHENTICATED`(理由は `JwtVerifier` のログとメトリクスだけ。ADR-0019 §5) |
  | スコープが足りない | `PERMISSION_DENIED` |
  | JWKS を取得できない | `UNAVAILABLE`(トークンの正否は分からない。ADR-0019 §5) |
- 検証は呼び出しの実行スレッド(イベントループではない)で待つ。grpc-kotlin のインターセプタは suspend にできない。`JwtVerifier` は JWKS の取得を上限つきの dispatcher で行うので(ADR-0019 §3)、共有の dispatcher は埋まらない。

## Alternatives Considered
- **shipping を REST で公開する**: Gateway の公開面と Rate Limit の枠が増える。社内の読み取りだけなので不採用。
- **shipping の情報を出さず、注文の状態で代用する**: 出荷の ID を返せず、ROADMAP の「出荷」を満たせない。不採用。
- **1 件ずつ読む RPC も用意する**: 使い方を 1 つにするほうが、N+1 を防げる。不採用。
- **`buf breaking` の区分を WIRE_JSON にする**: 項目名の変更を通してしまい、生成したスタブのコードが壊れる。不採用。
- **grpc-netty(内包していない版)を使う**: `ClientCertificateAllowList` を再利用できるが、Ktor と Netty の版をそろえ続ける必要がある。不採用。
- **証明書のエラーも `UNAVAILABLE` のまま再試行する**: 実装は簡単だが、設定の誤りが依存先の障害に見え、Circuit Breaker が開く。§8 のとおり判別できるので不採用。
- **証明書のエラーを、NonRetryable の既定どおり Circuit Breaker の成功に数える**: Half-Open を閉じてしまう。接続できていないのに「応答している」と扱うことになる。不採用。
- **mTLS だけにする(JWT を確かめない)**: 実装と処理は軽いが、§9 のとおり権限を細かく分けられない。不採用。
- **リフレクションを環境変数で切り替える**: 開発では便利だが、本番で誤って有効にする余地が残り、依存も増える。proto があれば `grpcurl` は使える。不採用。
- **JWT の検証をサービスの実装の中で呼ぶ**(suspend で書ける): 呼び忘れると認証なしで動く。不採用。
- **`Resilience` の打ち切りと同じ時刻を gRPC の deadline にする**: §6 のとおり、数え方が到着の順で変わる。不採用。

## Consequences(トレードオフ)
- `./gradlew build` のテストと contract-check の実行で、初回に buf を GitHub Releases から取得する(oasdiff と同じ)。
- 版を上げるときは、`libs.versions.toml` とチェックサムの表の両方を直す。
- FILE は厳しいので、項目名の修正も破壊的変更になる。設計中のうちに名前を固める。
- gRPC の deadline はサーバに 50 ミリ秒長く伝わる。呼び出し元が先に切れて取り消しが届くので、処理は残らない。
- JWT の検証で、呼び出しの実行スレッドが JWKS の取得を待つことがある(キャッシュがないときだけ)。
- 長い接続を 1 つの相手に張り続ける(`pick_first`)ので、複数台の負荷分散は Service Mesh か、クライアントの設定を足すまでできない(ADR-0008)。
- サービスが増えるたびに、証明書の SAN(`gen-dev-certs.sh`)と許可の一覧の設定が要る。

## 改訂履歴
- 2026-11-03: P13 ③ で inventory-service に実装した(gRPC のポート 9443。スコープ `inventory.stock:read`、`aud` `inventory-grpc`、許可の一覧は設定で必須)。
