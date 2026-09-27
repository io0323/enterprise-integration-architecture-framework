# ADR-0019: JWT の検証・スコープの認可・Client Credentials のトークン取得・SecretProvider
- Status: Accepted
- Date: 2026-09-27
- Framework 参照章: 12, 13, 14
- 関連: ADR-0008(Secrets の縮退)、ADR-0016 §7(Keycloak の realm と iss の固定)、ADR-0018(ログとマスキング)
- 番号: P04a のサブ PR ごとに予約した番号(0017 audit / 0018 observability / 0019 security)

## Context
P04a ③ で `platform/security` を作る。Framework 12.1 は、受信側での JWT の署名検証(公開鍵 / JWKS)と iss・aud・exp の検証、短命のトークン(≦1h)、長期有効の JWT の禁止を求める。12.4 は「JWT 無検証受入れ」と「Secrets のハードコード」をアンチパターンとしている。CLAUDE.md §5 は、JWT の iss・aud・exp の検証と、Secret を `SecretProvider` 経由で取得することを必須としている。

決める必要があるのは次の点。
1. JWT と JWKS のライブラリ
2. 受け付ける署名アルゴリズムと、時刻のずれの許容幅(leeway)
3. JWKS のキャッシュ・鍵のローテーション・IdP の障害時の扱い
4. Client Credentials のトークンのキャッシュと、失敗時の挙動
5. 401 / 403 / 503 の応答の形式
6. `SecretProvider` のローカルの実装の規則
7. 鍵・トークン・Secret をログとエラーに出さない方法

## Decision
### 1. JWT と JWKS は Nimbus JOSE+JWT を使い、Ktor は ktor-server-auth に独自の provider を載せる
版の確認日: 2026-09-27(Maven Central)。

| 成果物 | 版 | 状態 | 用途 |
|---|---|---|---|
| `com.nimbusds:nimbus-jose-jwt` | 10.10 | 安定版 | JWT の解析・署名の検証・JWKS の取得とキャッシュ |
| `io.ktor:ktor-server-auth` | 3.6.0 | 安定版(Ktor 本体と同じ版) | Ktor の Authentication に独自の provider(`eiaJwt`)を登録する |

| 評価の観点 | Nimbus JOSE+JWT(採用) | `ktor-server-auth-jwt`(auth0 の java-jwt + jwks-rsa) |
|---|---|---|
| 受け付けるアルゴリズムの固定 | `JWSVerificationKeySelector` に集合で渡す。集合にない `alg` の鍵は選ばれない | 使うアルゴリズムは JWKS の各鍵の `alg` から決まり、許可する集合を設定として明示・固定できない |
| JWKS のキャッシュ | `JWKSourceBuilder` で、キャッシュ・期限前の取り直し・未知の `kid` での取り直しと、その頻度の制限・IdP の障害時に直前の JWKS を使い続ける機能を組み合わせられる | キャッシュと頻度の制限はあるが、IdP が止まったときに直前の鍵を使い続ける仕組みは自分で作る必要がある |
| 推移的な依存 | なし(gson などは中に取り込み済み。BouncyCastle と Tink は任意) | java-jwt が Jackson 2(databind)を引き込む |
| 拒否した理由の区別 | 解析・鍵の選択・署名の検証を段ごとに呼べるため、理由をコードで決められる(例外のメッセージを解析しない) | 失敗は Ktor の challenge にまとめられ、理由の区別には例外の型とメッセージを見る必要がある |

- Nimbus の `DefaultJWTProcessor` は使わず、解析 → ヘッダ(`alg` / `typ`)→ 鍵の選択 → 署名 → クレームの順に `JwtVerifier` から呼ぶ。拒否の理由(`JwtRejectionReason`)を例外のメッセージから推測しないため。暗号の処理(署名の検証)は Nimbus の `JWSVerifier` が行う。
- クレームの時刻の判定は、注入した `kotlin.time.Clock` で行う(テストで時刻を固定するため)。
- Nimbus を参照してよいのは `platform/security` だけにする(Konsist の `nimbusOnlyInSecurity`。テストのソースセットは除く)。ほかのモジュールが署名の検証を個別に書かないようにするため。

### 2. 受け付ける署名アルゴリズム・leeway・有効期間の上限
- **既定で受け付けるアルゴリズム**: `RS256` / `PS256` / `ES256`。Keycloak の既定(RS256)と、RSA-PSS・ECDSA の代表である。
- **設定で指定できるアルゴリズム**: `RS256` / `RS384` / `RS512` / `PS256` / `PS384` / `PS512` / `ES256` / `ES384` / `ES512`。これ以外(HS 系・`none`・EdDSA・未知の名前)は、設定した時点で例外にする。
  - **HS 系を使えない理由**: JWKS で公開した公開鍵を HMAC の鍵にして HS256 で署名したトークンが、「トークンの `alg` に従って、手元の鍵で検証する」実装を通ってしまう(アルゴリズムの混同)。共通鍵のアルゴリズムは、JWKS(公開鍵)で検証する方式と両立しない。この攻撃のトークンを拒否することを単体テストで確かめている。
  - **`none` は常に拒否する**: 署名部が空のものも、別のトークンの署名を付けたものも拒否する。
  - **EdDSA を含めない理由**: Nimbus で Tink(任意の依存)が要る。必要になったら ADR を改訂する。
- **`typ`**: `JWT`(Keycloak が付ける)/ `at+jwt`(RFC 9068)/ なし を受け付ける。ID トークン(`id_token+jwt` など)をアクセストークンとして使わせない。
- **JWKS の鍵**: `kid`・アルゴリズムの鍵の種類・用途(`use=sig` か指定なし)が合う鍵だけを使う。Keycloak の JWKS には `use=enc`(RSA-OAEP)の鍵も含まれるが、署名の検証には使わない。
- **leeway(`clockSkew`)**: 既定 **30 秒**(上限 5 分)。`exp` は「現在時刻 ≥ exp + 30 秒」で期限切れとし、`nbf` と `iat` は「現在時刻 + 30 秒 < 値」で未来とする。Nimbus の既定(60 秒)より狭くした。NTP で同期したサーバ間のずれは通常 1 秒未満で、30 秒あれば、IdP とサービスの時計のずれとネットワークの遅延を吸収できる。
- **必須のクレーム**: `iss`(完全一致)・`aud`(期待する値を含む。文字列でも配列でもよい)・`exp`・`iat`。`nbf` はあれば検証する。
- **有効期間の上限(`maxTokenLifetime`)**: `exp - iat` が既定 **1 時間**を超えるトークンを拒否する(Framework 12.1「トークンは短命(≦1h)」「長期有効JWT禁止」)。上限は `JwtVerifierConfig` で変えられる。上限を判定するために `iat` を必須にした。
- 署名を確かめる前にクレームを判定しない。署名のないトークンに対して、クレームの判定結果(期限切れか、aud の不一致か)を観測させないため。

### 3. JWKS のキャッシュ・鍵のローテーション・IdP の障害
| 項目 | 既定値 | 意味 |
|---|---|---|
| `cacheTtl` | 5 分 | 取得した JWKS を使う時間 |
| `refreshAhead` | 30 秒 | 期限のこの時間前からは、リクエストを待たせずに裏で取り直す |
| `refreshTimeout` | 15 秒 | ほかのスレッドが取り直している間に待つ時間の上限 |
| `rateLimitMinInterval` | 30 秒 | 取り直しの最小の間隔 |
| `outageTolerance` | **15 分** | JWKS を取得できない間、最後に取得した JWKS を使い続ける時間の上限 |
| `connectTimeout` / `readTimeout` | 2 秒 / 2 秒 | JWKS の HTTP のタイムアウト |
| `sizeLimitBytes` | 50 KiB | JWKS の応答の大きさの上限 |

- **ローテーション**: 未知の `kid` のトークンが来たら、JWKS を取り直して探す。IdP が新旧の鍵を並べて公開する期間に、どちらの鍵のトークンも通る(`JwksRotationSpec`)。
- **頻度の制限**: 未知の `kid` のトークンを大量に送られても、取り直しは `rateLimitMinInterval` に 1 回まで。制限の中で鍵が見つからなければ **401(`unknown_key`)** にする。Nimbus はこの場合 `RateLimitReachedException`(`KeySourceException` の子)を投げるが、503 にすると、未知の `kid` を送るだけで 503 を返させられるため。
- **IdP の障害**: JWKS を取得できず、使える JWKS もない(起動直後か、`outageTolerance` を過ぎた)ときは、トークンの正否を判断できないため **503** を返す。401 にすると、クライアントがトークンを取り直して IdP にさらに負荷をかけるため。
- **`iss` と `jwksUri` は別々に設定する**(OIDC の discovery は使わない)。
  - コンテナの中からはバックチャネルの URL(`keycloak:8080`)で JWKS を取得する一方、`iss` はホストから見た URL(`localhost:19180`)に固定している(ADR-0016 §7)。
  - discovery の応答で取得先が変わると、検証の前提が設定の外で変わる。
  - 統合テストでは `KC_HOSTNAME` を上書きして `iss` を固定し、割り当てられたポートから JWKS とトークンを取得して、この構成で動くことを確かめている。
- **`outageTolerance` を 15 分にしたリスクと、受け入れる理由**:
  - **リスク**: 鍵の漏洩で IdP の鍵を差し替えた直後に JWKS を取得できないと、漏洩した鍵で署名したトークンが、最後に取得した JWKS で最長 15 分受け入れられる。キャッシュ(5 分)と合わせても、差し替えの後の受け入れは最長でおよそ 15 分である。
  - **受け入れる理由**:
    - IdP の短い障害(再起動・デプロイ・ネットワークの瞬断)のたびに、全サービスの API が 503 になるのを避ける。IdP は全連携の単一障害点であり、可用性への影響が大きい。
    - 鍵の漏洩は、IdP の障害と同時に起きる必要がある(漏洩だけなら、未知の `kid` か期限切れのキャッシュで取り直した時点で古い鍵は外れる)。
    - トークンの寿命(Keycloak の realm で 5 分)と有効期間の上限(1 時間)で、漏洩した鍵で作ったトークンの有効期間も限られる。
  - **緩和策**: 漏洩が分かったときは、IdP の鍵の差し替えに加えてサービスを再起動する(キャッシュを捨てる)。`outageTolerance` は設定で短くできる。高機密の連携では 5 分(`cacheTtl` と同じ値。それより短くはできない)にすることを推奨する。

### 4. Client Credentials のトークンのキャッシュと失敗時の挙動
- **再利用**: 期限の **30 秒前**(`refreshBefore`)まで同じトークンを返す。寿命が短いトークンでは、**寿命の 10%** との小さい方を使う(寿命 60 秒なら 6 秒前)。期限は「要求を送る前の時刻 + `expires_in`」で計算し、受信までの時間の分だけ早めに見積もる。`expires_in` のない応答はキャッシュしない。
- **同時の取得を 1 本にまとめる**:
  - 取得は `Mutex` の中で 1 つだけ走らせる。同時に 100 回呼ばれても要求は 1 回になる。
  - ロックを待っていた呼び出しは、待っている間に終わった取得の結果を使う(成功ならキャッシュ、失敗ならその失敗)。IdP の障害中に、待ち行列の全員が順に取り直して「待ち数 × タイムアウト」待たされることを防ぐ。
  - 期限前の取り直しの最中は、ほかの呼び出しを待たせずに期限内のトークンを返す。
- **タイムアウト**: 1 回の取得(接続から本文の読み取りまで)を既定 **5 秒**で打ち切る(`withTimeoutOrNull`)。
- **失敗時の挙動**:
  - 失敗はキャッシュしない。
  - 期限前の取り直しに失敗し、期限内のトークンがあれば、それを返して WARN を残す。次の取り直しは `refreshRetryInterval`(既定 5 秒)の後にする。
  - 期限切れの後の失敗は、そのまま失敗を返す。

  | 失敗 | 分類 | 備考 |
  |---|---|---|
  | タイムアウト・接続の失敗 | Retryable(`TokenEndpointUnavailable`) | 例外のメッセージは使わない |
  | 408 | Retryable | |
  | 429 | Retryable | `Retry-After`(秒数か HTTP-date)を `retryAfter` に入れる(kernel の `RetryPolicy` が使う) |
  | 5xx | Retryable | 503 などの `Retry-After` も入れる |
  | 400・401・403 などの 4xx | NonRetryable(`TokenRequestRejected`) | OAuth のエラーコード(`[a-z_]{1,64}` のときだけ)を持つ。Keycloak 26.7 は Secret の誤りに 401 `unauthorized_client` を返す(統合テストで確認) |
  | 応答の形式の不正 | NonRetryable(`InvalidTokenResponse`) | `access_token` がない・`token_type` が Bearer でない・`expires_in` が不正・JSON でない・64 KiB 超 |
  | Secret を取得できない | 設定の不備は NonRetryable、読み取りの一時的な失敗は Retryable(`ClientSecretUnavailable`) | 要求は送らない |

- **Retry と Circuit Breaker は P04b で結線する**(`platform/reliability`)。ここでは 1 回だけ要求し、上の分類で返す。同期呼び出しの 4 点セット(Framework 13)のうち、Timeout はここで、Retry・Circuit Breaker・Fallback(期限内のトークンを使い続けること)の結線は P04b で行う。
- **クライアントの認証(`client_secret_basic`)**: RFC 6749 §2.3.1 のとおり、クライアント ID と Secret を application/x-www-form-urlencoded でエンコードしてから `id:secret` を Base64 にする。Secret に `:` を含んでも区切りと混同されない。記号(`: + / %` 空白 `~ *` `= &`)を含む Secret で、単体テスト(エンコードの結果)と統合テスト(実際の Keycloak で認証が通ること)の両方を確かめている。
- **Secret は取得のたびに `SecretProvider` から読む**(ローテーションに追従するため)。
- **invalidate**: 下流が 401 を返したときに呼ぶ。キャッシュが別のトークンに替わっていれば何もしない(取り直したばかりのトークンを捨てないため)。

### 5. 401 / 403 / 503 の応答
| 状況 | ステータス | `WWW-Authenticate` |
|---|---|---|
| トークンがない・Bearer 以外の方式 | 401 | `Bearer`(realm を設定していれば `Bearer realm="..."`)。RFC 6750 §3.1 のとおり `error` を付けない |
| トークンが不正(形式・署名・クレーム)・`Authorization` が複数 | 401 | `Bearer error="invalid_token"` |
| スコープが足りない(`requireScopes`) | 403 | `Bearer error="insufficient_scope", scope="<必要なスコープを空白区切り>"` |
| JWKS を取得できず検証できない | 503 | なし |

- 本文は RFC 9457 の Problem Details の最小形(`{"type":"about:blank","title":"...","status":...}`、`application/problem+json`)にし、`Cache-Control: no-store` を付ける。**拒否した理由は応答に含めない**(攻撃者がトークンを作り直す手がかりになるため)。理由は DEBUG ログとメトリクス `eia.security.jwt.rejections`(属性 `reason`。値は `JwtRejectionReason` の固定の集合)で追う。
- `WWW-Authenticate` に入れるのは、設定の realm と、RFC 6749 §3.3 の scope-token の形式で検証済みのスコープだけ。リクエストから受け取った値は入れない(ヘッダの注入を防ぐ)。
- `requireScopes` は `authenticate { }` の中で使う。認証を通っていない(`authenticate(optional = true)` でトークンがない場合を含む)ときは 401 にし、処理を通さない。
- P05 で Problem Details を全体に導入するとき、`type` を連携標準のエラーの URI に揃える。

### 6. SecretProvider のローカルの実装(`EnvSecretProvider`。ADR-0008)
- 名前 `NAME`(`[A-Z][A-Z0-9_]*`、`_FILE` で終わる名前は使えない)に対して、`NAME`(値)か `NAME_FILE`(Docker secrets のファイルのパス)の**どちらか一方**を設定する。両方あるとき・どちらもないとき・値が空のときはエラーにする。
- ファイルは呼ばれるたびに読み直す。末尾の改行は 1 つ(`\n` か `\r\n`)だけ取り除く。64 KiB を超えるファイルと、UTF-8 でないファイルは設定の不備とする。
- ファイルがない・読めないときは Retryable(ファイルの差し替えの途中でありうる)、設定の不備は NonRetryable とする。
- `Secret` の `toString()` は `Secret(***)` を返す。値は `reveal()` でだけ取り出す。data class にしない(自動生成の `toString` / `copy` / `componentN` から値が漏れるため)。`equals` は定数時間で比べる。

### 7. 鍵・トークン・Secret をログとエラーに出さない
- **主な対策は、そもそも出さないこと**(ADR-0018 §3 と同じ考え方)。
  - `JwtVerifier` はトークン・クレームの値・Nimbus の例外のメッセージ(ヘッダやクレームの値を含むことがある)を、ログにも戻り値にも入れない。ログに残すのは理由のコードだけ。
  - トークンの取得は、応答の本文・Secret・トークンを、ログにもエラーにも入れない。接続の失敗は、例外の型の名前だけを DEBUG に残す。
  - `Secret` / `AccessToken` / `Credential.Bearer` の `toString()` は値を伏せる。`VerifiedToken` はトークンの文字列を持たない。
- **最後の防御**: ライブラリのログは中身を制御できない。たとえば Ktor の `HttpCallValidator` は、TRACE で例外をメッセージごと記録する。これは ② のマスキング(`EiaLogEncoder` / `OtlpLogAppender`。PEM・トークン・Basic 認証・秘密情報のキーの値を伏せる)で守る。本番の既定のログの水準(INFO)では、この TRACE のログは出ない。
- `NoLeakSpec` で次を確かめている。
  - ルートのロガーを TRACE にして、拒否した各種のトークン、トークンの取得の成功と失敗、PEM の秘密鍵を読む `SecretProvider` を動かす。
  - このモジュールのログは、生のメッセージにも、`EiaLogEncoder` の出力にも値が現れない。
  - ライブラリのログは、`EiaLogEncoder` の出力に値が現れない。
  - エラーのメッセージ・`toString`・HTTP の応答にも値が現れない。
  - 誤って鍵やトークンをログに書いても、`EiaLogEncoder` の出力では伏せられる。

### 8. Keycloak の realm は変えない(スコープはクライアントが要求する)
2026-09-27 に、realm `eiaf`(`infra/local/keycloak/realm-eiaf.json`)の `eiaf-e2e` クライアントが Client Credentials で発行するトークンを確かめた。
- `aud` の `order-api`(audience マッパー)は、既定のクライアントスコープで常に入る。
- `sales.order:read` / `sales.order:write` は optional のクライアントスコープで、`scope` パラメータで要求したときだけ入る(要求しなければ `scope` は空)。

`ClientCredentialsConfig.scopes` で必要なスコープを要求する方式にし、realm は変えない。
- 既定のクライアントスコープにすると、`sales.order:read` だけを要求しても write が付き、P05 のスコープ不足(403)の E2E を書けなくなる。
- 必要なスコープだけを要求するほうが、最小権限(Framework 12.3)に沿う。

## Alternatives Considered
- **`ktor-server-auth-jwt`(auth0 の java-jwt + jwks-rsa)**: §1 の表のとおり。受け付けるアルゴリズムを集合で固定できず、IdP の障害時の扱いを自分で作る必要があり、Jackson 2 を引き込む。不採用。
- **Nimbus の `DefaultJWTProcessor` をそのまま使う**: 実装は短くなる。ただし、拒否の理由が例外のメッセージにしか表れず(例: `Signed JWT rejected: Another algorithm expected, or no matching key(s) found`)、理由ごとのメトリクスとテストが、ライブラリのメッセージの文言に依存する。不採用。
- **OIDC の discovery で JWKS の URL を取得する**: 設定は `issuer` だけで済む。ただし、コンテナの中とホストで取得先が変わる構成(ADR-0016 §7)では、discovery の応答の `jwks_uri` が期待と異なりうる。検証の前提を設定で固定するため不採用。
- **leeway を Nimbus の既定(60 秒)にする**: 期限切れのトークンを受け入れる時間が長くなる。30 秒で足りるため不採用。
- **`outageTolerance` を使わない(IdP が止まったら、キャッシュの期限で 503 にする)**: 鍵の漏洩への耐性は上がるが、IdP の 5 分を超える障害で全 API が止まる。§3 のとおり可用性を優先する。高機密の連携は設定で短くする。
- **`outageTolerance` を無期限にする**: IdP の長い障害にも耐えるが、差し替えた鍵がいつまでも受け入れられうる。不採用。
- **トークンの取得の失敗をしばらくキャッシュする(ネガティブキャッシュ)**: IdP への負荷は減るが、復旧してもすぐには取得できない。同時の取得をまとめることと、期限内のトークンを使い続けることで足りるため不採用。Retry と Circuit Breaker は P04b で結線する。
- **`client_secret_post`(Secret をフォームの本文で送る)**: RFC 6749 §2.3.1 は Basic 認証をサポートすることを求め、本文での送信は推奨していない(NOT RECOMMENDED)。本文をログに出すプラグインがあると漏れやすいため不採用。
- **Secret の値を起動時に 1 回だけ読んでキャッシュする**: ファイルの差し替えによるローテーションに追従できないため不採用。
- **realm の既定のクライアントスコープに `sales.order:*` を入れる**: §8 のとおり不採用。

## Consequences(トレードオフ)
- JWT の検証の段(解析・ヘッダ・鍵・署名・クレーム)を自分で組み立てたため、Nimbus の `DefaultJWTProcessor` が将来加える検査は自動では入らない。Nimbus の版を上げたら、リリースノートと `JwtVerifierSpec` で確かめる。
- JWKS の取得は Nimbus の `DefaultResourceRetriever`(`HttpURLConnection`)で行う。サービスの Ktor Client(`ClientObservability` の traceparent・Correlation ID)を通らないため、JWKS の取得はトレースに出ない。
- `outageTolerance`(既定 15 分)の間、差し替えた鍵が受け入れられうる(§3)。
- 拒否した理由を応答に含めないため、クライアントの開発者は、サーバのログかメトリクスで理由を確かめる必要がある。
- トークンの取得は 1 回だけ要求し、リトライしない。P04b の結線までは、呼び出し側が `Retryable` を見て判断する。
- `EnvSecretProvider` はファイルを毎回読むため、呼び出しの多い経路でキャッシュせずに使うと I/O が増える。トークンの取得はトークンのキャッシュの外側でしか Secret を読まないため、問題にならない。
- ローカルの Keycloak は http(`sslRequired: none`)のため、トークンと Client Secret は平文で流れる(ADR-0008 の転送路の暗号化の縮退。#29)。本番の構成では https の `tokenEndpoint` と `jwksUri` を使う。
