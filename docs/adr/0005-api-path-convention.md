# ADR-0005: API パス規約(Gateway 公開パスとサービス内部パス)
- Status: Accepted
- Date: 2026-09-25
- Framework 参照章: 5.2, 5.3, 16.1

## Context
Framework 16.1 の Naming は API を `/{domain}/{resource}`、5.3 の Versioning は URI パス方式 `/v1/` と定めている。
INTEGRATION_STANDARDS と CLAUDE.md は `/v{n}/{resource}` だけを規定しており、両者を組み合わせた最終形が決まっていない。

## Decision
- **Gateway 公開パス**: `/{domain}/v{n}/{resource}`(例: `/sales/v1/orders`)。命名からドメイン(所有者)とバージョンが分かる。
- **サービス内部パス**: `/v{n}/{resource}`(例: `/v1/orders`)。サービスは自身のドメイン prefix を持たない。
- APISIX のルートで `/{domain}` prefix を外して upstream に転送する(`proxy-rewrite` の `regex_uri`)。
- OpenAPI 契約の `servers` には Gateway 公開 URL(`/{domain}` まで)を記載し、`paths` は `/v{n}/...` で書く。
- `domain` は連携 ID の `{DOMAIN}` を小文字にした値(`INT-SALES-001` → `sales`)と一致させる。
- contract-check で「`servers[*].url` が `/{domain}` で終わること」「`paths` が `/v{n}/` で始まること」を検査する。

## Alternatives Considered
- `/v{n}/{domain}/{resource}`: バージョンがドメインを跨いで共有されているように見え、ドメインごとに独立して進化できることが伝わりにくい。不採用。
- サービス側も `/{domain}/v{n}/...` で実装する: Gateway を経由しない内部呼び出しでもドメイン名を知る必要があり、ドメインの再編時に全サービスの改修が必要になる。不採用。

## Consequences(トレードオフ)
- Gateway のルート定義に書き換え設定が 1 つ増える。
- 設計書 16.1、INTEGRATION_STANDARDS §1、CLAUDE.md §5 を 2 層構成の表記に統一する。

## 改訂履歴
- 2026-10-01: P05 ⑤c で、APISIX のルート `/sales/v1/*` → order-service の `/v1/*` を実装した(`infra/local/apisix/apisix.yaml`。ADR-0023 §1)。
