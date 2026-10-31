# 取引先の台帳と、鍵・ホスト鍵の有効期限(B2B。ADR-0040)

取引先の台帳(TPA。Framework 11.1)は 1 つのファイルで、b2b-gateway と、送信を b2b-gateway に移す(P12 ⑦)までの file-exchange が読む。
ローカル基盤では `infra/local/b2b/partners.json`。形は `platform/trading-partner/src/main/resources/trading-partner/ledger.v1.schema.json`。

- 台帳には秘密情報の**名前**だけを書く。値(ホスト鍵の known_hosts の行・クライアントの秘密鍵・PGP の公開鍵)は SecretProvider から読む
  (compose では `<名前>_FILE` で `secrets/` のファイルを渡す)。
- 台帳は起動時に読む。台帳を直したら、台帳を読むサービスを再起動する(形・取引先の重複・扱う文書ごとに要る設定の誤りは、起動時に設定の誤りになる)。

## アラート
| アラート | 条件 | 重さ |
|---|---|---|
| `TradingPartnerCredentialExpiringSoon` | 期限まで 30 日を切った | warning |
| `TradingPartnerCredentialExpiringCritical` | 期限まで 7 日を切った | critical |
| `TradingPartnerCredentialExpired` | 期限が切れた | critical |

- メトリクスは `eia_trading_partner_credential_expiry_seconds`(期限の UNIX 秒。ラベル `trading_partner_id`・`trading_partner_credential`)。
- 鍵の種類(`trading_partner_credential`):
  - `sftp_host_key`: 取引先の SFTP のホスト鍵。期限は台帳の `sftp.hostKey.validUntil`(SSH の鍵そのものには期限がないので、TPA で合意した差し替えの期日)
  - `sftp_client_key`: 自社が接続に使うクライアントの鍵。期限は `sftp.clientKey.validUntil`
  - `pgp_public_key`: 取引先の PGP の公開鍵。期限は `pgp.validUntil` と、**鍵の中の期限のうち早いほう**
- 台帳の期日は、その日の終わり(UTC)まで有効として数える。

## 期限が近いとき
1. どの取引先の、どの鍵かを、アラートのラベルで確かめる。
2. 台帳の連絡先(`contacts` の `operations`)に連絡し、差し替えの日程を決める。
   - ホスト鍵: 取引先が新しいホスト鍵を作る。フィンガープリントを、取引先と**別の経路**(電話など)で照合してから受け取る(初めての接続で覚えない。ADR-0032 §8)。
   - クライアントの鍵: 自社が新しい鍵を作り、公開鍵を取引先に渡す。取引先が登録したことを確かめてから、秘密情報を差し替える。
   - PGP の公開鍵: 取引先が鍵を更新するか、期限を延ばす。主鍵のフィンガープリントを別の経路で照合する(`gpg --show-keys <ファイル>`)。
3. 秘密情報のファイルを差し替え、台帳の `validUntil` を新しい期日に直す。台帳を読むサービスを再起動する。
4. メトリクスの期限が新しい期日になり、アラートが消えたことを確かめる。

## 期限が切れたとき
- 送受信が失敗している(ホスト鍵の不一致・認証の失敗・暗号化の拒否)。b2b-gateway と file-exchange のログで、どの取引先の、どの呼び出しが失敗しているかを確かめる。
- 「期限が近いとき」の手順で、すぐに差し替える。取引先の障害時の連絡先(`contacts` の `incident`)にも、送受信が止まっていることを伝える。
- 差し替えの後、送れなかったファイルは file-exchange の運用者のコマンド(`receive <名前>`。docs/runbooks/file-exchange.md)で送り直す。

## 取引先を足す
1. TPA(フォーマット・コード体系・再送の規則・締め時刻・障害時の連絡)を文書で合意する(Framework 11.1)。
2. 台帳(`partners.json`)に取引先を足す。
   - `id`(英小文字・数字・ハイフン。32 文字まで)と `name`
   - `edifact`: 取引先が送る交換の UNB の送り手(識別子と識別の機関)。EDI の文書を受け取る取引先では必須
   - `documents`: 扱う文書(連携カタログの ID と向き。ファイルは `dataset` も)。カタログにあること・EDI かファイルの連携であることを、`platform/trading-partner` のテストがローカル基盤の台帳で確かめる
   - `sftp`: 接続先・ホスト鍵とクライアントの鍵(秘密情報の名前と期日)・ディレクトリ(`outbound` = 送るファイル、`inbound` = 取引先が置く文書、`acknowledgements` = 返す応答)・書き終わりの判断(`completion`。既定は改名 `rename`、取引先が改名できなければ `stable`)
   - `pgp`: 取引先の PGP の公開鍵(ファイルを送る取引先では必須)
   - `agreement`: 通貨・肯定の APERAK を返すか・業務の応答の期限・締め時刻・CONTRL の SLA・再送の規則
   - `contacts`: 連絡先(個人ではなく役割の窓口。`business`・`operations`・`incident`)
3. 秘密情報(ホスト鍵・クライアントの鍵・PGP の公開鍵)を、フィンガープリントを照合してから置く。
4. 台帳を読むサービスを再起動する。
