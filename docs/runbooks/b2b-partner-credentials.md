# 取引先の台帳と、鍵・ホスト鍵の有効期限(B2B。ADR-0040)

取引先の台帳(TPA。Framework 11.1)は 1 つのファイルで、b2b-gateway と、送信を b2b-gateway に移す(P12 ⑦)までの file-exchange が読む。
ローカル基盤では `infra/local/b2b/partners.json`。このファイルはコミットせず、`make env` がテンプレート(`infra/local/b2b/partners.template.json`)から作る。
テンプレートの鍵の期日は `@KEYS_VALID_UNTIL@` で、`make env` が鍵を作った日から 1 年後(`secrets/partner01-keys.valid-until`)を書き込む
(固定の期日をコミットすると、日が経つだけで期限のアラートが出始め、verify と e2e が失敗するため。ADR-0040 §3)。
開発用の鍵を作り直すと期日も作り直す。期日が近づいたら `secrets/partner01-keys.valid-until` を消して `make env` を実行する。形は `platform/trading-partner/src/main/resources/trading-partner/ledger.v1.schema.json`。

- 台帳には秘密情報の**名前**だけを書く。値(ホスト鍵の known_hosts の行・クライアントの秘密鍵・PGP の公開鍵)は SecretProvider から読む
  (compose では `<名前>_FILE` で `secrets/` のファイルを渡す)。
- 台帳は起動時に読む。台帳を直したら、台帳を読むサービスを再起動する(形・取引先の重複・扱う文書ごとに要る設定の誤りは、起動時に設定の誤りになる)。

## アラート
| アラート | 条件 | 重さ |
|---|---|---|
| `TradingPartnerCredentialExpiringSoon` | 期限まで 30 日を切った | warning |
| `TradingPartnerCredentialExpiringCritical` | 期限まで 7 日を切った | critical |
| `TradingPartnerCredentialExpired` | 期限が切れた | critical |
| `B2bDocumentRejected` | 取引先の文書を拒否した(段階ごと。下の「拒否した文書」) | warning |
| `B2bInboxStuck` | 受け付けに失敗したファイルが、処理中のディレクトリに 15 分残っている(下の「受け取りのディレクトリ」) | warning |
| `B2bInboxUnavailable` | 取引先の受け取りのディレクトリを 15 分見られない(下の「期限が切れたとき」) | warning |
| `B2bAcknowledgementSendFailing` | 応答を送れない状態が 15 分続いている(下の「応答の送信」) | warning |
| `B2bOrderSubmissionFailing` | 受け付けた注文を order の API に取り込めない状態が 15 分続いている(下の「社内への取り込み」) | warning |
| `B2bAperakDeadlineApproaching` / `B2bAperakDeadlinePassed` | 業務の応答の期限まで 1 時間を切った / 過ぎた(下の「社内への取り込み」) | warning / critical |
| `B2bOrderSubmissionExpired` | 送り直しの上限を過ぎた注文がある(下の「送り直しの上限を過ぎた注文」) | critical |
| `B2bFileDeliveryFailing` | 取引先にファイルを送れない状態が 15 分続いている(下の「送るファイル」) | warning |

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
     取引先が署名の鍵を差し替えるときは、下の「取引先の PGP の鍵を差し替える」の手順で、移行の間は新旧の両方の署名を受け付ける。
3. 秘密情報のファイルを差し替え、台帳の `validUntil` を新しい期日に直す。台帳を読むサービスを再起動する。
4. メトリクスの期限が新しい期日になり、アラートが消えたことを確かめる。

## 期限が切れたとき
- 送受信が失敗している(ホスト鍵の不一致・認証の失敗・暗号化の拒否)。b2b-gateway と file-exchange のログで、どの取引先の、どの呼び出しが失敗しているかを確かめる。
- 「期限が近いとき」の手順で、すぐに差し替える。取引先の障害時の連絡先(`contacts` の `incident`)にも、送受信が止まっていることを伝える。
- 差し替えの後、送れなかったファイルは file-exchange の運用者のコマンド(`receive <名前>`。docs/runbooks/file-exchange.md)で送り直す。

## 取引先を足す
1. TPA(フォーマット・コード体系・再送の規則・締め時刻・障害時の連絡)を文書で合意する(Framework 11.1)。
2. 台帳に取引先を足す(ローカル基盤ではテンプレート `partners.template.json`。鍵の期日は本番では合意した日付を書く)。
   - `id`(英小文字・数字・ハイフン。32 文字まで)と `name`
   - `edifact`: 取引先が送る交換の UNB の送り手(識別子と識別の機関)。EDI の文書を受け取る取引先では必須
   - `documents`: 扱う文書(連携カタログの ID と向き。ファイルは `dataset` も)。カタログにあること・EDI かファイルの連携であることを、`platform/trading-partner` のテストがローカル基盤の台帳で確かめる
   - `sftp`: 接続先・ホスト鍵とクライアントの鍵(秘密情報の名前と期日)・ディレクトリ(`outbound` = 送るファイル、`inbound` = 取引先が置く文書、`acknowledgements` = 返す応答)・書き終わりの判断(`completion`。既定は改名 `rename`、取引先が改名できなければ `stable`)
   - `pgp`: 取引先の PGP の公開鍵(必須。B2B の文書は送るものも受け取るものも暗号化と署名をする。Framework 9.2・ADR-0040 §8)
   - `agreement`: 通貨・肯定の APERAK を返すか・業務の応答の期限・締め時刻・CONTRL の SLA・再送の規則
   - `contacts`: 連絡先(個人ではなく役割の窓口。`business`・`operations`・`incident`)
3. 秘密情報(ホスト鍵・クライアントの鍵・PGP の公開鍵)を、フィンガープリントを照合してから置く。
4. 台帳を読むサービスを再起動する。

## 取引先の PGP の鍵を差し替える
取引先は、新しい鍵で署名した文書をいつから送るかを、TPA の連絡で知らせる。こちらは、移行の間だけ新旧の両方の署名を受け付ける(ADR-0040 §8)。
1. 新しい公開鍵を受け取り、主鍵のフィンガープリントを別の経路で照合して、秘密情報として置く(新しい名前で)。
2. 台帳の `pgp.publicKeySecret` を新しい鍵の名前にし、`validUntil` を新しい期日にする。旧い鍵の名前は `pgp.previousPublicKeys` に、
   受け付ける期日(`acceptUntil`。移行の期間の終わり)と一緒に移す。台帳を読むサービスを再起動する。
   - これ以降、送る文書(売上ファイル・CONTRL・APERAK)は新しい鍵で暗号化する(取引先は新しい鍵の秘密鍵で読む)。
   - 受け取る文書は、新旧どちらの鍵の署名でも受け付ける。受け取りの記録(`b2b.received_document.signer`)に、署名した鍵のフィンガープリントが残る。
3. 取引先が新しい鍵だけで署名するようになったことを、受け取りの記録の `signer` で確かめる。
4. `acceptUntil` を過ぎると、旧い鍵の署名は台帳を書き換えなくても受け付けない(「取引先の鍵でない署名」として、復号の段階で拒否する)。
   落ち着いたら、`previousPublicKeys` から旧い鍵を消し、秘密情報も片付ける。

## 読めない文書(復号の段階で拒否)
- 復号できない・取引先の鍵でない署名・改竄の検知に失敗した文書は、原本(暗号文)を保管し、受け取りの記録(`outcome = UNREADABLE`、`refusal` に理由のコード)と
  監査(段階 `decryption`、結果 `failure`)に記録する。交換の中身が読めないので CONTRL は返さない。
- 取引先の連絡先(`contacts` の `operations`)に、受け取った時刻・ファイル名・理由のコードを伝え、鍵(取引先が使った自社の公開鍵・署名の鍵)を確かめてもらう。
  - `pgp_unknown_signer`: 取引先が、台帳にない鍵で署名した(鍵の差し替えの連絡が漏れていないか)
  - `pgp_not_for_us`: 自社の公開鍵あてに暗号化されていない(取引先が持っている自社の公開鍵が古くないか)
  - `pgp_not_encrypted`・`pgp_malformed`・`pgp_no_integrity`・`pgp_unsigned`: 取引先の製品の設定(暗号化・署名・改竄の検知)

## 受け取りのディレクトリ
b2b-gateway は、取引先の SFTP の受け取りのディレクトリ(台帳の `sftp.directories.inbound`)を見回り、次のように動かす(ADR-0040 §4)。

| ディレクトリ | 置くもの |
|---|---|
| `{inbound}` | 取引先が置いたファイル(書き終わったものだけを拾う。`rename` なら一時名のファイルは拾わない) |
| `{inbound}/processing` | 押さえたファイル(処理が終わるまで) |
| `{inbound}/processed` | 処理が終わったファイル(`{受け取りの記録の ID}-{名前}`) |

- 処理中に残り続けるファイルは、受け付けに失敗し続けている(DB・S3・鍵の読み込み、大きさの上限 16 MiB の超過など)。b2b-gateway のログで、そのファイルの失敗の理由を確かめる。
  原因を直せば、次の見回りで続きから処理する(受け付けはやり直しても重複しない)。
- 処理済みのファイルは消してよい(原本は Object Lock の置き場にある)。消す時期は取引先との取り決めで決める。

## 応答の送信
b2b-gateway は、受け付けのときに送信待ちで記録した応答(CONTRL・APERAK)を、取引先の SFTP の応答のディレクトリ(台帳の `sftp.directories.acknowledgements`)に送る(ADR-0040 §7)。
- 送るのは、受け付けのときに取引先の公開鍵あてに暗号化し、原本として保管した暗号文そのもの(`originals/{取引先}/sent/{種類}/{SHA-256}.pgp`)。取引先の側の名前は `{contrl・aperak}-{応答の記録の ID}.edi.pgp`。
- 記録は `b2b.acknowledgement`(`state` が `PENDING` なら送信待ち、`SENT` なら送った。`remote_name` が取引先の側の名前)。監査は `b2b.ack.send`。
- 送信待ちに残り続ける応答は、送信に失敗し続けている(取引先の SFTP の障害・鍵の期限切れ・原本の読み出しの失敗)。b2b-gateway のログで、その応答の失敗の理由を確かめる。
  原因を直せば次の回に送る。取引先の側にすでに同じ名前のファイルがあれば(前の送信が、送った印を付ける前に落ちた)、送り直さずに送った印を付ける(`already_present = true`)。
- `original_mismatch` は、保管した原本の SHA-256 が記録と違う(置き場の誤り)。送らずに止めるので、原本の置き場(版)と記録を確かめる。
- 取引先が応答を受け取っていないと言うときは、`remote_name` と `sent_at` を伝える。取引先が同じ交換を送り直せば、同じ応答が新しい名前でもう一度届く。

## コードの対応表
b2b-gateway は、取引先が注文に書いたコード(GLN・取引先の商品コード・GTIN)を、取引先ごとの対応表で社内の値に直す(ADR-0040 §6)。
表にないコードの注文は、業務の段階で APERAK(RE)で拒否する(ORD001 買い手・ORD002 届け先・ORD004 商品)。

| 種類 | 見出し(この並びで) |
|---|---|
| `buyers` | `gln,customer_id,country_code,postal_code,region,city,line1,line2` |
| `delivery-parties` | `gln,country_code,postal_code,region,city,line1,line2` |
| `items` | `code_type,code,product_id,sku`(`code_type` は `gtin` か `buyer_part_number`) |

- 買い手の住所は、NAD+DP(届け先)のない注文に使う既定の届け先。`region`・`line2` は空でよい。
- 取り込みは種類ごとに**丸ごと入れ替える**(ファイルにない行は消える)。取引先から受け取った最新の表の全体を、1 つのファイルにして取り込む。
  - ファイルは UTF-8(BOM はあってもよい)の CSV。値にカンマ・改行・`"` を含むときは `"` で囲み、`"` は `""` と書く。
  - 誤りが 1 つでもあれば何も変えず、行の番号・列の名前・理由を表示する(値は表示しない)。直してから取り込み直す。
  - 空のファイル(見出しだけ)は取り込めない。取引先との取引をやめるときは、台帳から取引先を外す。
- 取り込むたびに表の版が 1 進み、監査に `b2b.codes.import`(運用者・ファイルの SHA-256・版・行の数)が残る。今の版は `b2b.code_table` で確かめる。
- 受け付けた後の注文は、受け付けの時点の表で社内の値に直してある(要求の本文を固定する。下の「社内への取り込み」)。表を入れ替えても、送信待ちの注文は変わらない。
- 取り込みのコマンド:
  ```bash
  b2b-gateway codes import <取引先> <buyers|delivery-parties|items> <CSV のファイル> --operator <運用者>
  ```
  - アプリのロールの資格情報と台帳(`B2B_GATEWAY_PARTNERS_FILE`)で動く(所有者のパスワードを環境に渡さない)。`--operator` は監査の「誰が」に残る運用者の名前。
  - ローカル基盤では `make b2b-codes PARTNER=partner01 KIND=items FILE=items.csv OPERATOR=ops-1`(ファイルをコンテナに読み込み専用で渡す)。
  - 終了コード: 0 = 入れ替えた(版と行の数を表示)、2 = 使い方・ファイルの誤り(何も変えていない)、1 = DB の失敗など(やり直してよい)。

## 社内への取り込み
業務の段階を通った注文は、受け付けの時点で送信待ち(`b2b.order_submission`。`state = PENDING`)になり、b2b-gateway が order の API に送る(ADR-0040 §6)。
- order が受け付けたら `ACCEPTED`(`order_id` が order の注文の ID)。取引先が肯定の応答を選んでいれば(台帳の `agreement.acknowledgements.positiveAperak`)、APERAK(AP)を送る。
- order が業務の誤り(400・422)で受け付けなければ `REFUSED`(`refused_status`)。取引先には APERAK(RE)を、固定の文言 `APR901 ORDER NOT ACCEPTED BY SUPPLIER SYSTEM` だけで返す。
  order が返した理由は取引先に返さない。b2b-gateway のログと order の監査で確かめ、必要なら取引先の連絡先(`contacts` の `business`)に伝える。
- 送れなかった注文は送信待ちのまま残り、次の回に同じ本文と Idempotency-Key で送り直す(`attempts` と `last_error` に試みた回数と失敗のコード)。
  - `http_call_unavailable`: order・Gateway・Keycloak の一時的な障害。直れば送られる
  - `http_call_rejected`(403): `eiaf-b2b-gateway` のスコープ(`sales.order:write`)の設定を確かめる
  - `idempotency_key_reused`: 同じキーで違う本文を送った(固定した本文を書き換えた・キーの作り方の誤り)。送り直しても直らないので、開発者が調べる
- **業務の応答の期限**(台帳の `agreement.acknowledgements.aperakDeadline`。受け取った時刻から)が近い・過ぎた送信待ちは、アラート `B2bAperakDeadlineApproaching`(残り 1 時間)・`B2bAperakDeadlinePassed` になる。
  order の API が動いているか(Gateway 経由の `/sales/v1/orders`)と、上の `last_error` を確かめる。期限を守れないときは、取引先の連絡先(`contacts` の `operations`)に遅れを伝える。

## 送り直しの上限を過ぎた注文
受け取ってから 12 時間(送り直しの上限。order の Idempotency-Key の保存期間 24 時間より短い)を過ぎても送れなかった注文は、送らずに `EXPIRED` にし、
アラート `B2bOrderSubmissionExpired` になる。自動では送り直さない(保存期間を過ぎた後に送ると、order に同じ注文が 2 つできうるため)。
1. `b2b.order_submission` で、期限切れの注文(`order_number`・`idempotency_key`・`last_error`)を確かめる。
2. order の側で、その注文がすでに登録されていないかを確かめる(顧客と注文の内容。期限切れの前の試みで、登録まで進んでいた場合がある)。
3. 登録されていなければ、取引先の連絡先(`contacts` の `business`)と相談し、手で登録するか、取引先に同じ注文を新しい交換で送り直してもらう
   (送り直しの交換は新しい管理番号で届くので、新しい送信待ちになる。同じ注文番号は ORD007 で拒否されるので、取引先には新しい注文番号で送ってもらう)。
4. 取引先には APERAK を自動では返さない。結果を連絡先に伝える。

## 拒否した文書
アラート `B2bDocumentRejected`(取引先・段階ごと。メトリクス `eia_b2b_documents_total` の `b2b_stage`)は、取引先の文書を拒否したことを運用者に知らせる。
取引先には、構文・スキーマの段階は CONTRL、業務の段階は APERAK で、標準のコードと仕様の固定の文言だけを返している(社内の情報は返さない。ADR-0041 §5)。
1. `b2b.received_document`(`outcome`・`stage`・`refusal`)と監査(`b2b.document.receive` の `details`)で、どの文書をどの段階で拒否したかを確かめる。
2. 段階ごとの対応:
   - `decryption`: 応答を返せない。上の「読めない文書」の手順で、取引先の連絡先に知らせる
   - `syntax`・`schema`: 取引先の EDI の製品の設定や、仕様(`contracts/files/edi/`)の版の食い違い。取引先が CONTRL を受け取っているかを確かめ、必要なら調整する
   - `business`: 対応表(買い手・届け先・商品。上の「コードの対応表」)・合意した通貨・注文番号の重複など。対応表の不足なら、取引先から最新の表を受け取って取り込み直す
3. 同じ取引先で拒否が続くときは、取引先の連絡先(`contacts` の `operations`)と原因を確かめる。

## 監査の改竄の検査
- b2b-gateway は監査のハッシュチェーンの末尾を、アンカーとして `eiaf-audit` の `anchors/b2b-gateway/` に定期的に保存する(`B2B_GATEWAY_AUDIT_ANCHOR_INTERVAL`。ADR-0017 §5)。
- 改竄の検査は `make audit-verify SERVICE=b2b-gateway`(終了コード 0 / 1 / 2。`docs/runbooks/audit-verify.md`)。
- 受け取った文書の原本は `eiaf-b2b-originals` に Object Lock(COMPLIANCE)で置いてある。監査の `payload_sha256` と原本の SHA-256 を照らし合わせれば、原本が受け取ったものと同じことを確かめられる。

## 送るファイル
日次の売上ファイル(INT-SALES-004)は、受け渡しの場所(バケット `eiaf-file-exchange` の `outbound/sales-daily/`)から、b2b-gateway が台帳でデータセットを受け取る取引先に送る(ADR-0040 §7。P12 ⑦。⑦b の切り替えまでは file-exchange が直接送る)。
- b2b-gateway は受け渡しの場所を毎回すべて一覧し、ファイルと取引先の組ごとに送信の記録(`b2b.file_delivery`)がないものだけを送る。過去の日付で作り直したファイル(`docs/runbooks/batch-rerun.md`)を後から置いても送る。
- 取引先の側の名前は `{データファイル}.pgp`(取引先あてに暗号化し、自社の鍵で署名)。送る前に取引先の側に同じ名前があれば、送らずに記録する(`already_present`)。監査は `b2b.file.send`。
- **送れないとき**(`B2bFileDeliveryFailing`):
  - 取引先ごと: 取引先の SFTP・鍵・ホスト鍵(上の「期限が切れたとき」)。直れば次の回(`B2B_GATEWAY_DELIVER_INTERVAL`。既定 1 分)に送る。
  - `unknown`: 受け渡しの場所を読めない(`eiaf-b2b-handoff` の資格情報・S3 の障害)か、データファイルの SHA-256 がマニフェストと違う(`handoff_mismatch`。写しの誤り)。b2b-gateway のログでファイルの名前を確かめ、受け渡しの場所に正しいファイルとマニフェストを置き直す。
- **取引先が届いていないと言うとき**: `b2b.file_delivery` の `sent_at` と `remote_name` を伝える。送り直すときは、その組の記録を残したまま取引先の側のファイルを消してもらっても送り直さない(記録があるため)。送り直しが必要なら、新しい名前のファイルとして作り直す。
- 受け渡しの場所の保存期間: 送り終えたファイルは、マニフェストの作成の時刻から `B2B_GATEWAY_OUTBOUND_RETENTION`(既定 14 日)を過ぎたら b2b-gateway が消す。送れていないファイルは消さない。原本(file-exchange が受け取ったファイル)は archive にある。
