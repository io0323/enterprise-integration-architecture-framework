# Runbook: Webhook の署名の鍵の差し替え

## 対象
- CRM(SaaS)の Webhook の署名の鍵(Standard Webhooks の形。ADR-0035 §2)。送り手(ローカルは saas-mock)と受け口(webhook-receiver)が共有する。
- 鍵はファイルで置き、**要求のたびに読む**。ファイルを置き換えるだけで差し替えられ、コンテナの再起動は要らない。
  ローカルの置き場は `infra/local/secrets/crm-webhook/`(`.gitignore` 済み。`make env` が最初の鍵を作る)。

| ファイル | 読むもの | 意味 |
|---|---|---|
| `receiver-current` | webhook-receiver(`WEBHOOK_RECEIVER_CRM_SECRET_FILE`) | 今の鍵。必須(読めなければ受け口は 503 を返し、送り手に再送させる) |
| `receiver-previous` | webhook-receiver(`WEBHOOK_RECEIVER_CRM_SECRET_PREVIOUS_FILE`) | 差し替えの間だけ置く、差し替え前の鍵。どちらの鍵の署名も受け付ける |
| `sender-current` | saas-mock(`SAAS_MOCK_WEBHOOK_SECRET_FILE`) | 送り手が署名に使う鍵(本物の SaaS では、SaaS の管理画面の設定) |
| `sender-next` | saas-mock(`SAAS_MOCK_WEBHOOK_SECRET_NEXT_FILE`) | 送り手が新旧の両方で署名する間だけ置く(Standard Webhooks の送り手の機能) |

- 定期的な差し替え(例 1 年ごと)と、鍵が漏れた疑いのあるとき(すぐに)に行う。
- 本物の SaaS では、鍵の差し替えの仕組み(新旧の両方での署名の有無・切り替えの手順)を相手ごとに確かめる(ADR-0035 §2)。
- 鍵の値をログ・チケット・チャットに貼らない。ファイルは `umask 077` で書く。

| アラート | 重大度 | 見る節 |
|---|---|---|
| `WebhookPreviousKeyStillUsed` | info | [送り手が新しい鍵に切り替えない](#送り手が新しい鍵に切り替えない) |
| `WebhookSignatureRejected`(`reason="webhook_signature_invalid"`) | warning | [差し替えの後に署名が拒否されるとき](#差し替えの後に署名が拒否されるとき) |

## まず見るもの
- ダッシュボード **SaaS — CRM customers**(Grafana のフォルダ EIAF)の「一致した鍵(差し替えの進み具合)」。`current` と `previous` の数。
- `eia_webhook_requests_total{key="previous"}` が増えていれば、送り手はまだ差し替え前の鍵で署名している。

## 差し替えの手順
順番を守る。**受け口が新しい鍵を受け付けるようにしてから、送り手を切り替える**(逆だと、その間の Webhook が 401 で拒否され、送り手の再送に頼ることになる)。

1. 新しい鍵を作る(ファイルに直接書き、画面に出さない):
   ```bash
   cd infra/local/secrets/crm-webhook
   umask 077
   printf 'whsec_%s\n' "$(head -c 24 /dev/urandom | base64 | tr -d '\n')" > new-key
   ```
2. **受け口**: 今の鍵を差し替え前の鍵にし、新しい鍵を今の鍵にする(どちらの署名も受け付ける)。
   ```bash
   cp receiver-current receiver-previous
   cp new-key receiver-current
   ```
   確かめる: 送り手はまだ古い鍵で署名しているので、ダッシュボードで `previous` が増え、拒否(`webhook_signature_invalid`)が増えないこと。
3. **送り手**: 新しい鍵で署名するように切り替える。ローカルの saas-mock は、次のどちらかで行う。
   - そのまま切り替える: `cp new-key sender-current`
   - 新旧の両方で署名する期間を置く(本物の SaaS が対応していれば、受け口の更新と順序を気にせずに済む):
     `cp new-key sender-next` の後、時間を置いて `cp new-key sender-current && rm sender-next`
4. **確かめる**: ダッシュボードで `previous` が増えなくなり、`current` だけが増えること。送り手の再送の期間(saas-mock の既定は 3 時間余り)が
   過ぎるまで待つ(古い鍵で署名した再送が残っているため。再送は新しい時刻で署名し直すが、鍵は送った時点の鍵)。
5. **受け口**: 差し替え前の鍵を外す。
   ```bash
   rm receiver-previous new-key
   ```
6. 確かめる: `make verify PROFILE=saas`(署名した Webhook が届くこと)。

鍵が漏れた疑いのあるときは、手順 3 の後、手順 4 の待ちを短くしてよい(古い鍵で署名した Webhook は、漏れた鍵で作られたものと区別できないため、
受け付け続ける危険の方が大きい)。拒否した Webhook は記録されないので、送り手の管理画面から送り直してもらう。

## 送り手が新しい鍵に切り替えない
`WebhookPreviousKeyStillUsed`(差し替え前の鍵の署名が 1 日を超えて続く)。

- 送り手の設定(本物の SaaS は管理画面)が新しい鍵になっているかを確かめる。ローカルでは `sender-current` が `receiver-current` と同じかを
  `cmp sender-current receiver-current` で確かめる(値を表示しない)。
- 送り手が切り替えるまで、`receiver-previous` を外さない(外すと 401 で拒否され、送り手の再送が尽きると取りこぼす)。

## 差し替えの後に署名が拒否されるとき
`WebhookSignatureRejected` が差し替えの直後に出た。

- 手順の順番を確かめる: 送り手が新しい鍵に切り替わったのに、受け口の `receiver-current` が古い鍵のまま、などの不一致。
  `cmp` で送り手と受け口のファイルを比べ、手順 2 からやり直す。
- 鍵のファイルの改行の数・余分な空白を確かめる(末尾の改行は 1 つだけ取り除かれる。`whsec_` の後の base64 が壊れていると一致しない)。
- 拒否した Webhook は記録していない。送り手の再送(新しい時刻で署名し直す)で、鍵を直した後に届く。再送を尽くした Webhook は、
  ローカルでは saas-mock の管理の API(`POST /admin/webhooks/{id}/redeliver`)で送り直す。取りこぼした変更は定期の同期(15 分ごと)が補う。

## 参考
- ADR-0035 §2(署名・鍵の差し替え)、ADR-0019 §6(SecretProvider)
- `platform/webhook`(`StandardWebhooks`・`WebhookVerifier`)、webhook-receiver の `WebhookSecrets`、saas-mock の `WebhookDispatcher`
