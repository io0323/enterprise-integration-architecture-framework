# Runbook: 開発用の CA と mTLS の証明書(make certs)

## 対象
- ローカル基盤の mTLS(APISIX → order-service。ADR-0008・ADR-0024 §6)の証明書を作る・作り直すとき。
- order-service が「証明書の有効期限が切れています」で起動しないとき。
- ゲートウェイからサービスへの接続が TLS のハンドシェイクで失敗するとき。

仕組みは ADR-0024 §6・§7 を参照。

## 置き場所と中身
`infra/local/certs/`(.gitignore 済み。**コミットしない**)。`infra/local/scripts/gen-dev-certs.sh` が作る。

| ファイル | 内容 | 有効期間 | 使うもの |
|---|---|---|---|
| `ca.crt` / `ca.key` | 開発用 CA(`CN=EIAF Local Dev CA`) | 90 日 | 証明書の署名。`ca.key` はコンテナに渡さない |
| `order-service.crt` / `.key` | サーバ証明書(SAN `order-service`・`localhost`) | 30 日 | order-service の API のポート(8443) |
| `apisix.crt` / `.key` | ゲートウェイのクライアント証明書(SAN `apisix`) | 30 日 | APISIX から order-service への接続(P05 ⑤c) |

- 鍵は EC P-256 の PKCS#8(`BEGIN PRIVATE KEY`)で、権限は 0600。コンテナは、`make certs` を実行した利用者の uid で動いて読む。**root(uid 0)では `make up` と `make certs` は止まる**(ADR-0024 §7)。root 以外の利用者で実行する。
- order-service は、クライアント証明書を CA の署名に加えて SAN の許可の一覧(`ORDER_TLS_ALLOWED_CLIENTS`。既定 `apisix`)でも確かめる。

## 自動の作り直し
`make up` と `make certs` のたびに、次を確かめる。

| 状態 | 動き |
|---|---|
| CA がない・残りが 7 日を切っている・切れている | CA を作り直し、**すべての証明書を作り直す** |
| 証明書がない・今の CA で検証できない・残りが 7 日を切っている・切れている | その証明書だけを作り直す |
| どれも有効で、残りが 7 日以上 | 何もしない |

- 作り直すと `infra/local/certs/.renewed` を残す。起動中のコンテナは証明書を起動時にしか読まないので、次の `make up` が、証明書を使うコンテナ(Makefile の `CERT_CONSUMERS`)を作り直して入れ替え、`.renewed` を消す。
- `make certs` だけを実行した場合は、続けて `make up PROFILE=<起動中の profile>` を実行する。

```bash
make certs                 # 確かめて、必要なら作り直す
make up PROFILE=order      # 作り直した証明書をコンテナに読み込ませる(ここでも certs を確かめる)
make verify PROFILE=order  # mTLS なしの接続を拒否すること・ゲートウェイの証明書なら届くことを確かめる
```

## order-service が起動しない(証明書の有効期限)
ログ(`make logs SERVICE=order-service`)に、次のような行が出る。

```
起動できません: ORDER_TLS_CERT_FILE: 証明書の有効期限が切れています(notAfter=2026-10-31T04:21:33Z)。make certs で作り直してください(docs/runbooks/dev-certificates.md)
```

- 先頭の名前が、どの証明書かを示す(`ORDER_TLS_CERT_FILE` = サーバ証明書、`ORDER_TLS_CLIENT_CA_FILE` = CA)。
- 対処: `make up PROFILE=order`(証明書を作り直し、コンテナを作り直す)。
- 「まだ有効ではありません(notBefore=…)」のときは、ホストか Docker の VM の時計がずれている。時計を直してから `make up`。
- 「証明書か鍵の形式が不正です」「読めません」のときは、`infra/local/certs/` を消してから `make certs`(手で編集したファイルや、途中で止まった生成を作り直す)。

残りが 7 日を切っている証明書で起動した場合は、起動はするが WARN(「証明書の有効期限が近づいています」)を残す。`make up` で作り直す。

## ゲートウェイからの接続が失敗する
- order-service のログに「許可の一覧にないクライアント証明書の接続を閉じました」が出る: クライアント証明書の SAN が `ORDER_TLS_ALLOWED_CLIENTS` にない。`openssl x509 -in infra/local/certs/apisix.crt -noout -ext subjectAltName` で確かめる。
- CA を作り直した後に、片方のコンテナだけが古い CA を使っている: `make up` で入れ替える(`.renewed` が消えていたら、`docker compose ... rm --stop --force order-service` の後に `make up`)。

## 本番では
開発用 CA と、このスクリプトは使わない。証明書の発行・配布・ローテーションは、Service Mesh か証明書の自動ローテーションの基盤で行う(ADR-0008)。
