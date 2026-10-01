# ADR-0023: API Gateway(APISIX)のルート・JWT・mTLS・トレース・Rate Limit
- Status: Accepted
- Date: 2026-10-01
- Framework 参照章: 5.2, 5.4, 5.5, 12.1, 12.3, 14.1
- 関連: ADR-0005(API パス規約)、ADR-0008(mTLS の範囲)、ADR-0018 §2・§5(トレースの伝搬・ParentBased のサンプラ)、ADR-0019(JWT の検証)、ADR-0022(Problem Details・冪等)、ADR-0024 §3・§6・§7(予算・サービス側の mTLS・利用者)
- 番号: P05 で予約した番号(0022 platform/api / 0023 gateway / 0024 サービスの実行時の構成)

## Context
P05 ⑤c で、order-service を APISIX(3.18。standalone の YAML)経由で公開する(ROADMAP P05: JWT 検証・Rate Limit・Correlation ID の付与・prefix の書き換え・mTLS)。次を決める。

1. ルート(公開パスの書き換え・上流のタイムアウト)
2. JWT をゲートウェイでも検証するか
3. ゲートウェイ → サービスの mTLS(クライアント証明書の渡し方・サービスの証明書の検証)
4. 外部から受け取った `traceparent` の扱い(ROADMAP P05。ADR-0018 の Consequences で持ち越した)
5. クライアント単位の Rate Limit
6. ゲートウェイ自身が返すエラーの形式

## Decision
### 1. ルート
- 公開パス `/sales/v1/*` を `proxy-rewrite` の `regex_uri` で `/v1/*` に書き換えて、order-service(`order-service:8443`)に送る(ADR-0005)。
- **上流のタイムアウトは connect 2 秒・send / read 15 秒**。リクエストの予算(10 秒。ADR-0024 §3)より長くし、サービスが予算切れの 503 で応答する前にゲートウェイが切らないようにする。
- **`retries: 0`**。POST を二重に送らない(冪等はサービスが保証するが、ゲートウェイの再送は不要な処理を増やす)。

### 2. JWT はゲートウェイと order-service の両方で検証する
- ゲートウェイ: `openid-connect` の `bearer_only` + `use_jwks` で、署名・`exp`・`iss`・`aud`(`order-api` を含む)・`azp`(ある)を確かめる。スコープ(`sales.order:*`)は order-service だけが確かめる(ルートごとに違うため。ADR-0019)。
- order-service: これまでどおり完全に検証する(ADR-0019。`requireClientId` を含む)。
- 理由:
  - **Rate Limit のキー(§5)は、署名を確かめた後の `azp` でなければならない。** 確かめない値をキーにすると、ほかのクライアントの `azp` を名乗った偽のトークンで、そのクライアントの枠を使い切れる。
  - 不正なトークンを入口で止め、サービスまで届けない。
  - サービスでも検証を続けるのは、ゲートウェイを通らない内部の呼び出しがあり、ゲートウェイの設定の誤りにも備えるため(ゼロトラスト。Framework 12.3)。
- **期待する `iss` と JWKS の URL は別々に設定する**(⑤c の最初に確かめた)。
  - `discovery`(JWKS の取得): コンテナから届く `http://keycloak:8080/realms/eiaf/.well-known/openid-configuration`。Keycloak は `KC_HOSTNAME_BACKCHANNEL_DYNAMIC` のため、この文書の `jwks_uri` もコンテナから届く URL になる。
  - `claim_validator.issuer.valid_issuers`: トークンに入っている `http://localhost:19180/realms/eiaf`。
  - 確かめたこと: 正しいトークンは通り、期待する `iss` を別の値にすると 401(ログに `Claim 'iss' … returned failure`)。トークンなし・署名の途中の改ざん・ペイロードの改ざん・`alg=none` は 401。
- `aud` と `azp` は `claim_schema`(JSON Schema)で確かめる。満たさなければ 401 `invalid_token`(サービスの ADR-0019 §5 と同じ 401)。`claim_validator.audience` は不一致を 403 にするため使わない。
- `bearer_only` + `use_jwks` では `client_id` / `client_secret` を使わない。スキーマの必須項目なので値を置く(`client_id` は `order-api`、`client_secret` は `not-used-bearer-only`。資格情報ではない)。

### 3. ゲートウェイ → サービスの mTLS
- クライアント証明書は開発用 CA の `apisix`(SAN `apisix`。ADR-0024 §6 の許可の一覧)。upstream の `tls.client_cert` / `client_key` は PEM の**中身**を取る項目なので、ファイルを直接は指定できない。
  - compose の apisix の entrypoint が、起動時に `certs/apisix.crt` と `certs/apisix.key` を読み、APISIX のプロセスの環境変数にだけ渡す(`apisix.yaml` の `${{GATEWAY_CLIENT_CERT}}` などで参照する)。compose の `environment` には書かないので、`docker inspect` には出ない。
  - APISIX のコンテナは、鍵(0600)を読むためにホストの利用者の uid で動かし、グループは 0 にする(root の利用者ではない)。APISIX のイメージは `conf/` と `logs/` をグループ root に書き込み可にしていて(任意の uid で動かす環境のため)、グループ 0 がないと起動時に設定を生成できない。root では `make up` が止まる(ADR-0024 §7)。
- **サービスのサーバ証明書を検証する。** APISIX 3.18 の upstream の `tls.verify` は「Kafka の上流だけ」が対象で、HTTP の上流には効かない(イメージの `schema_def.lua` で確かめた。別の CA を信頼させても通ってしまった)。そこで `nginx_config.http_server_configuration_snippet` で `proxy_ssl_verify on` を加える。CA は `apisix.ssl.ssl_trusted_certificate`(開発用 CA)、名前は APISIX が設定する `proxy_ssl_name $upstream_host`(`pass_host: node` で `order-service`)。確かめたこと: 別の CA を信頼させると 502(`upstream SSL certificate verify error`)。

### 4. 外部の traceparent は捨て、ゲートウェイでトレースを始める
- ゲートウェイは、外部から受け取った `traceparent` / `tracestate` を捨て、自分の span を起点(親なし)にしてトレースを始め、サービスには自分の `traceparent` を送る。
- 理由: サービスのサンプラは ParentBased(ADR-0018)なので、外部の `sampled=1` がそのまま効く。外部の呼び出し元が、こちらのトレースの量(コスト)と ID を決められてしまう。社外の trace_id を社内のトレースに混ぜると、関係のないトレースがつながることもある。
- **取引先のトレースを信じてよい条件**(将来、B2B の連携で外部の `traceparent` を受け入れる場合): 次をすべて満たす専用のルートだけにする。
  1. 呼び出し元を mTLS(または同等の強さの認証)で確かめていて、その相手との契約(カタログ)に、トレースの連携を明記している。
  2. その相手からの `sampled` フラグを、こちらのサンプリングの上限(率か件数)で抑える。外部の指定だけでサンプリングが増えないようにする。
  3. 受け取った trace_id を、ゲートウェイの span のリンク(または属性)として残し、親子にはしない形を優先する。親子にするのは、相手と同じトレースの基盤で運用する場合に限る。
  - 一般の公開ルート(この ADR の `/sales/v1/*`)は、どれにも当たらないので捨てる。
- 実現の方法: `opentelemetry` プラグイン(優先度 12009)は、受信したヘッダを無条件に親として読む(無視する設定がない。nginx に headers-more のモジュールもない)。そこで、global_rules で、その前(優先度 12010)に `traceparent` と `tracestate` を消す(§5 の独自のコード 1)。
- **既存の不具合を直した**: APISIX 3.18 の `opentelemetry` は、送信先とリソースを `plugin_metadata` から読む。これまでの設定(`config.yaml` の `plugin_attr`)では、要求のたびに「plugin_metadata is required」の警告を出して何もしておらず、Tempo に `service.name=apisix` の span がなかった(P03 からの不具合。#8 の「APISIX の OTel の確認」で見つかった)。`apisix.yaml` の `plugin_metadata` に移した。
- `X-Correlation-Id` は `request-id` プラグインで、なければ付け(UUID)、応答にも返す。受け取った値はそのまま送り、形式の検査と採番し直しはサービスが行う(ADR-0018 §3)。span の属性 `x-correlation-id` にも入れる。

### 5. クライアント(azp)ごとの Rate Limit と、独自のコード
- `limit-count` で、クライアント(`azp`)ごとに 60 件 / 60 秒。超えたら 429 + `Retry-After`(整数の秒)+ Problem Details `rate-limited`(Framework 5.4)。
- **独自のコード(`serverless-*` プラグインのインラインの Lua)を 3 つ使う。どれも、APISIX 3.18 の設定だけでは実現できないことを確かめた。**
  | # | 場所(フェーズ・優先度) | 処理 | 設定だけでできない理由 |
  |---|---|---|---|
  | 1 | global_rules・rewrite・12010 | 受信した `traceparent` / `tracestate` を消す | §4 のとおり。`opentelemetry` に受信したヘッダを無視する設定がなく、nginx でヘッダを消すモジュール(headers-more)もない |
  | 2 | ルート・access・2000(`openid-connect` の 2599 の後、`limit-count` の 1002 の前) | 検証したトークンの `azp` を `X-Eiaf-Client-Id` に入れる。`X-Userinfo` は上流に送らない | `limit-count` のキーは nginx の変数の組み合わせだけで、JWT のクレームを取り出せない。`openid-connect` は検証したクレームを `X-Userinfo`(base64 の JSON)に入れるが、そのままキーにすると `exp`・`jti` を含むため、トークンごとの枠になる。jwt-auth の Consumer をクライアントごとに作る方法は、公開鍵を設定に固定する必要があり、Keycloak の鍵のローテーションに追従できない |
  | 3 | ルート・header_filter | ゲートウェイ自身の 401 / 429 を `application/problem+json` にし、429 に `Retry-After` を付ける(上流の応答は変えない) | `_meta.error_response` で本文は変えられるが、Content-Type は変えられない。`limit-count` は `Retry-After` を付けず、`X-RateLimit-Reset` は小数(例 `29.42`)で、`Retry-After` は整数の秒(RFC 9110 §10.2.3)でなければならない。`response-rewrite` は 1 つのルートに 1 つで、状態コードごとの処理と計算ができない |
- **偽のヘッダを使わせない**: Rate Limit のキーの `X-Eiaf-Client-Id` は、外部から来たものを `proxy-rewrite`(rewrite のフェーズ。コード 2 より前)で必ず消し、コード 2 が検証したトークンの `azp` だけを入れる。`X-Userinfo` は、クライアントが送ったものを `openid-connect` が検証の前に消し、検証したクレームで入れ直す。
- **独自のコードの振る舞いは `make verify PROFILE=order` で確かめる**:
  - コード 1: 送った trace_id が使われず、Tempo の apisix の span が起点で、order-service の span の親になる。
  - コード 2: 2 つ目のクライアント(`eiaf-e2e-b`)の枠が、`eiaf-e2e` が偽の `X-Eiaf-Client-Id: eiaf-e2e-b` と偽の `X-Userinfo` を付けて送った要求で減らない。`eiaf-e2e` が 429 になっても、`eiaf-e2e-b` は 429 にならない。
  - コード 3: 401 と 429 が `application/problem+json`、429 の `Retry-After` が正の整数。
- 2 つ目のクライアント `eiaf-e2e-b` を realm に加えた(スコープの扱いは `eiaf-e2e` と同じ。ADR-0019 §8 の方針は変えない)。Keycloak は realm を初回だけ取り込むので、このクライアントより前に作ったボリュームでは `make clean` が要る。

### 6. ゲートウェイ自身が返すエラーの形式
| 状況 | 状態コード | 本文 |
|---|---|---|
| トークンがない・不正(§2) | 401 | Problem Details `unauthorized`(`_meta.error_response` + §5 のコード 3) |
| Rate Limit を超えた(§5) | 429 | Problem Details `rate-limited` + `Retry-After` |
| サービスに接続できない・証明書の検証の失敗 | 502 | Problem Details `about:blank`(nginx の `error_page`) |
| 上流のタイムアウト(接続か読み取り) | 504 | Problem Details `about:blank`。**結果は分からない**(契約の `GatewayTimeout`) |
- ゲートウェイの本文は固定の文なので、`correlationId` は入れない(応答のヘッダの `X-Correlation-Id` で追う)。`error_page` の内部の転送でヘッダが落ちるため、502 / 504 では要求の `X-Correlation-Id` を応答に付け直す。
- サービスの予算切れの 503 `deadline-exceeded`(確定していない)と、ゲートウェイの 504(分からない)を区別する(ADR-0024 §3)。

## Alternatives Considered
- **JWT は order-service だけで検証する**: Rate Limit のキーに、検証していない `azp` を使うことになる(§2)。IP アドレスの単位にすると、NAT の後ろの複数のクライアントが 1 つの枠を共有する。不採用。
- **外部の traceparent を信じる**: §4 の理由。不採用(条件を満たす専用のルートに限って、将来の ADR で許す)。
- **jwt-auth の Consumer をクライアントごとに作る**: `key_claim_name` で `azp` を Consumer に結び付けられるが、公開鍵を設定に固定する必要があり、Keycloak の鍵のローテーション(ADR-0019 §3)に追従できない。不採用。
- **鍵をコンテナの環境変数(compose の environment)で渡す**: `docker inspect` で誰でも読める。不採用。
- **APISIX を root で起動し、鍵を読んでから apisix の利用者に切り替える**(`setpriv`): 起動の一部を root で動かすことになる。ホストの uid とグループ 0 で動かす形にした。不採用。
- **upstream の `tls.verify` で検証する**: 3.18 では HTTP の上流に効かない(§3)。APISIX を上げて使えるようになったら、`proxy_ssl_verify` の追加をやめてこちらに移す。

## Consequences(トレードオフ)
- 独自のコード 3 つは、APISIX の版を上げるときに、優先度とフェーズ(`openid-connect` 2599・`limit-count` 1002・`opentelemetry` 12009)が変わっていないかを確かめる必要がある。`make verify PROFILE=order` が振る舞いを確かめる。
- JWKS を 2 か所(ゲートウェイとサービス)で取得する。Keycloak の鍵のローテーションは、両方のキャッシュの更新を待つ。
- `X-Eiaf-Client-Id` は order-service にも届く。order-service はこのヘッダを使わず、信頼しない(クライアントはトークンの `azp` で決める)。
- ゲートウェイの 401 / 429 / 502 / 504 の本文には `correlationId` がない(ヘッダにはある)。
- Keycloak の discovery と OTLP の送信は平文(ローカルの縮退。ADR-0008。APISIX が起動時に WARN を出す)。
- Rate Limit は `policy: local`(ゲートウェイの各ワーカーではなく、ノードの共有メモリで数える)。ゲートウェイを複数台にするときは `redis` などの共有の保存先が要る。

## 改訂履歴
- 2026-10-01: 作成(P05 ⑤c)。
