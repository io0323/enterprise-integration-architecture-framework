# ADR-0035: Webhook の受信と SaaS の Connector
- Status: Accepted
- Date: 2026-10-21
- Framework 参照章: 3, 5, 10, 12, 13, 16

## Context
P10(SaaS / Webhook / iPaaS-like Flow。#13)では、SaaS(CRM。ローカルは saas-mock)と社内をつなぐ。Framework 10 章は、SaaS の API や Webhook の仕様に全社が引きずられないよう、統合の層で SaaS 固有の仕様を隔離することを求める。
- SaaS のイベントは Webhook で受け、署名を検証し、社内のイベントに変えて出す(Webhook を社内に直接配らない)。
- SaaS の API の認証・ページング・レート制限は、統合の層で吸収する。
- アンチパターン: Webhook の署名の未検証、SaaS 同士の直結、iPaaS の中の業務のロジック(ADR-0036)。

Webhook は到達の保証が弱く、送り手は 2xx 以外で再送する。順序も保証されない(Framework 3.2)。

## Decision
### 1. 構成(受信と処理を分ける)
```
SaaS ──署名つき Webhook──▶ Gateway(署名のルート・Rate Limit)──▶ webhook-receiver
                                                                    │ 検証 → 受け取りの記録 + Outbox(同じトランザクション)→ 2xx
                                                                    ▼
                         社内の取り込みのトピック integration.crm-webhook.received.v1(INT-INTEGRATION-001)
                                                                    ▼
SaaS の API ◀──定期の同期── integration-flow(Flow の定義: ADR-0036)──Outbox──▶ sales.customer.changed.v1(INT-SALES-007)
```
- **webhook-receiver** は、署名の検証・受け取りの記録・2xx を返すことだけを行う。本文は解析しない。
- 処理(Canonical への変換・振り分け)は integration-flow が後で行う。両者は社内の取り込みのトピックでつなぐ(読むのは integration-flow だけ)。
  integration-flow が receiver の DB を読む形は、Shared Database になるので取らない。

### 2. 署名(Standard Webhooks の形)
- ヘッダ: `webhook-id`(イベント ID。再送でも同じ)・`webhook-timestamp`(UNIX 秒)・`webhook-signature`(`v1,<base64>` を空白区切りで 1 つ以上)。
- 署名は `{webhook-id}.{webhook-timestamp}.{本文のバイト列}` の HMAC-SHA256。鍵は SaaS と共有した秘密(SecretProvider から読む)。
- **本文を解析する前に、受け取った生のバイト列で検証する**(解析して並べ直した本文では一致しない。解析器の脆弱性を、認証の前にさらさない)。
- **比較は定数時間**(`MessageDigest.isEqual`。一致した長さで時間が変わると、署名を推測される)。
- **リプレイ**: `webhook-timestamp` が受け口の時刻から **前後 5 分** を外れたら拒否する(401)。盗まれた正しい Webhook を後で送り直されても受け付けない。
- **正当な再送**は、送り手が **再送のたびに新しい時刻で署名し直す**(Standard Webhooks の決まり)ので、数時間後の再送でも受け付ける。
  saas-mock はこの決まりに従う。**本物の SaaS がこの決まりに従っているか(元の時刻のまま再送してこないか)は、相手ごとに確かめる必要がある**
  (従わない相手には、許容の幅を広げるのではなく、相手の再送の仕様に合わせた別の受け口の設定を、相手ごとに ADR を改訂して決める)。
- **鍵の差し替え**: 差し替えの間は、新旧 2 つの鍵(`{name}` と `{name}_PREVIOUS`)のどちらで署名されていても受け付ける。
  送り手が新しい鍵に切り替えたことを確かめてから、古い鍵を外す(手順は ⑥ の Runbook)。
- 失敗(署名の不一致・時刻の範囲外・ヘッダの欠落)は記録せず、理由ごとにメトリクスで数える。

### 3. 受け取りの記録・重複・個人情報
- 受け取りの記録 `webhook_receipt` と、取り込みのトピックへの Outbox の行を、**同じトランザクション** で書いてから 2xx(204)を返す(ADR-0007)。
  処理は後で行う(送り手のタイムアウトと再送を増やさない)。
- **重複は Webhook のイベント ID で判定する**(`webhook_receipt` の主キー)。同じ ID の再送には、Outbox に書かずに 204 を返す。
- **個人情報**(顧客の名前・メール)は、`webhook_receipt` に **保存しない**。記録するのは、重複の判定と調査に要るもの
  (イベント ID・送り手・本文の SHA-256・署名の時刻・受け取りの時刻)だけ。本文は Outbox を通して取り込みのトピックに流すだけにする。
- 取り込みのトピック `integration.crm-webhook.received.v1` は **confidential**。**保持期間は 7 日**(`dataRetention: P7D`。トピックの
  `retention.ms` と一致させる。⑥)。integration-flow が止まったときの再処理に足り、個人情報を長く持たない長さ。
  Outbox の行は、発行の後に掃除する(ADR-0007)。
- 本文の形の誤り(署名は正しい)は 2xx で受け付け、取り込みの側(integration-flow)で DLQ に隔離する。拒否し続けると送り手の再送が増える。

### 4. 順序
- Webhook の順序は保証されない前提にする。新旧は、本文の **版**(CRM の顧客の `version`)で判断する(ADR-0036 の ordering)。
- Webhook の経路と、SaaS の API の定期の同期の経路は、**同じ新旧の記録**(キーごとの最新の版)を使う。どちらから来ても、記録より古い版は出さない。

### 5. Gateway
- 公開パス `/sales/v1/webhooks/crm` → サービス内部 `/v1/webhooks/crm`(ADR-0005)。**JWT ではなく署名で認証するルート**(openid-connect を付けない)。
- 送り手(IP)ごとの Rate Limit と、本文の大きさの上限(256 KiB)を Gateway でかける。署名の検証は受け口で行う
  (Gateway では、生のバイト列の HMAC・2 つの鍵・時刻の検査を扱いにくい)。

### 6. SaaS の API の呼び出し(Connector)
- integration-flow の Connector が、SaaS の API をカーソルでページングして読む(定期の同期。Webhook の取りこぼしの補い)。
- **429 と `Retry-After`** は、Retryable のエラーの `retryAfter` に写して、その時間だけ待ってから再試行する。
- Timeout・Retry・Circuit Breaker は、依存先ごとに 1 つの Resilience(名前 `saas-crm`)を **ResilienceMetrics** から作る(ADR-0021。#8-c)。
- 応答は **Tolerant Reader** で読む(未知の項目は無視する)。ベンダーは予告なく項目を足す。

### 7. カタログ
| ID | 内容 |
|---|---|
| INT-SALES-005 | CRM の Webhook の受け口(自社の契約 `contracts/openapi/crm-webhook.v1.yaml`。送り手は外部) |
| INT-SALES-006 | CRM の顧客の API(外部の契約 `contracts/external/crm-api.v1.yaml`) |
| INT-INTEGRATION-001 | 社内の取り込みのトピック `integration.crm-webhook.received.v1`(confidential・7 日) |
| INT-SALES-007 | Canonical の顧客のイベント `sales.customer.changed.v1`(ADR-0036) |

### 8. 外部の契約
- 持ち主がベンダーの契約(SaaS の API)は、こちらで使う部分の写しを **`contracts/external/`** に置く。
- カタログの owner は、**連携に責任を持つ社内のチーム**(`team-integration`)にし、`external: { vendor, source }` で外部の契約であることと出典を書く
  (owner をベンダーにすると、カタログの「Owner はチーム単位」と、異動しても消えない窓口の規則を満たせない。Framework 16.2)。
  外部の契約を使う連携は external が必須(`CC-CATALOG-007`)。
- contract-check は、外部の契約に **自社の API の規則**(`/{domain}`・`/v{n}/`・Idempotency-Key・429・security)**を当てない**。
  構造の検証(`CC-STRUCT-002`)・ファイル名(`CC-NAMING-009`)・登録(`CC-CATALOG-006`)・ライフサイクルの検査は当てる。
- **ベンダーが破壊的変更をしたら**、写しを直す。contract-check は、こちらでは止められない変更なので、違反ではなく **警告**
  (`CC-EXTERNAL-001`。Step Summary に出る)にする。その PR で取り込みの側(Connector・Flow の定義)を合わせる。

### 9. 自社の Webhook の受け口の契約
- OpenAPI の操作に `x-eia-webhook: true` を付ける。contract-check は、Idempotency-Key の代わりに、署名の 3 つのヘッダ(required)と、
  security の署名の方式(`apiKey`・`in: header`・`name: webhook-signature`)を検査する(`CC-API-004`)。429 の応答は従来どおり必須。

## Alternatives Considered
| 案 | 採らない理由 |
|---|---|
| 受け口で変換して社内のイベントを直接出す | 受け口の処理が重くなり、送り手のタイムアウトと再送が増える。変換の誤りで Webhook を拒否すると、送り手が再送し続ける |
| integration-flow が receiver の DB を読む | Shared Database の禁止(CLAUDE.md §8) |
| `webhook_receipt` に本文を保存する | 個人情報の置き場所が増える。重複の判定には ID とハッシュで足りる |
| 時刻の許容の幅を広くして(例 24 時間)古い時刻の再送も受け付ける | リプレイの窓が広がる。正当な再送は新しい時刻で署名し直すので、幅を広げる必要はない |
| Gateway で署名を検証する | 生のバイト列の HMAC・2 つの鍵・時刻の検査を APISIX の設定だけで扱うのは難しく、鍵が Gateway にも散らばる |
| 外部の契約の破壊的変更を違反にする | ベンダーの変更は止められず、無関係の PR の CI が失敗する。気づくことが目的なので警告にする |
| カタログの owner にベンダー名を書く | チーム単位の Owner の規則を満たせず、社内の窓口が分からない |

## Consequences(トレードオフ)
- 受け取りから社内のイベントまでに、Outbox・Debezium・取り込みのトピック・integration-flow の段が増える(遅延は SLO の範囲。INT-SALES-007 は p99 30 秒)。
- 本物の SaaS ごとに、署名の形・再送の時刻・鍵の差し替えの仕様を確かめる必要がある(§2)。Standard Webhooks に従わない相手は、ADR を改訂して決める。
- 外部の契約の写しは、ベンダーの変更に気づいたときに直す運用になる(contract-check はベンダーの変更を自動では検知しない。写しを直したときに警告で知らせる)。

## 改訂履歴
- 2026-10-21: P10 ①(#13)で作成した。
- 2026-10-21: P10 ③ で webhook-receiver を実装した。§2 の鍵の名前は `WEBHOOK_RECEIVER_CRM_SECRET` と `WEBHOOK_RECEIVER_CRM_SECRET_PREVIOUS`(要求のたびに読む)。拒否の Problem Details の種類 `webhook-signature-invalid`・`webhook-timestamp-out-of-range`(401)を INTEGRATION_STANDARDS §6 に加えた。受け口は order と同じく mTLS だけで受ける(ADR-0024 §6。実装は platform/security に移した。#141)。§3 の受け取りの記録の保持期間は 30 日(7 日より短くはできない。送り手の再送の期間より長くする)。メトリクスは `eia.webhook.requests`(`outcome`・`reason`・`key`)。`key=previous` が続く間は、送り手がまだ新しい鍵に切り替えていない。同じ webhook-id で本文が違う再送は、先の本文を正として取り込まず、`outcome=duplicate_body_mismatch` で数える。
