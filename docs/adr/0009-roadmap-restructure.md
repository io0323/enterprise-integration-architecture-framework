# ADR-0009: ROADMAP の構成変更(P00 キックオフレビュー)
- Status: Accepted
- Date: 2026-09-25
- Framework 参照章: 7, 12, 13, 14, 18

## Context
キックオフレビューで、ROADMAP 初版(P00〜P14)について次の問題を確認した。
- P04 が observability・security・reliability・messaging-kafka を 1 フェーズで扱っており、PR が大きくなりすぎる。messaging-kafka は P06 まで使われず、先回りの抽象化になる。
- Audit と mTLS(Framework 12.1・14.1)を扱うフェーズがない。
- P08 は ETL だけで、Framework 7.1 が既定とする ELT を扱っていない。
- P05 の DoD「Tempo でトレースを確認」を検証できるダッシュボードが P14 までない。
- E2E テストが P14 にまとまっており、それまでのフェーズのシナリオを自動で回帰できない。
- P01 の DoD「全ターゲットのテスト成功」は、ubuntu の CI では満たせない(ADR-0004)。

## Decision
1. **P04 を P04a / P04b に分割する**
   - P04a Platform: Observability, Security & Audit(`platform/observability`, `platform/security`, `platform/audit`)
   - P04b Platform: Resilience(`shared/resilience`(KMP)と `platform/reliability`)
   - `platform/messaging-kafka` は、P06(Producer と Outbox)と P07(Consumer、DLQ、Replay)で、最初に使うサービスと同時に作る。
2. **Audit の土台を P04a に入れる**。P12 で B2B の原本保管とつなげる(ADR-0008)。
3. **mTLS** を P05(APISIX → サービス)と P13(gRPC)に入れる(ADR-0008)。
4. **P08 の範囲を ELT(raw ロード + SQL 変換)と ETL(機密マスキング)の両方にする**。`platform/batch` の DAG ランナーも P08 で作る(ADR-0008)。
5. **最低限の RED ダッシュボードを P05 に前倒しする**。
6. **`tests/e2e` は P05 で骨組みを作り、各フェーズで DoD のシナリオを追加する**。P14 は障害注入と SLO / NFR 計測のまとめに専念する。
7. **P01 の DoD を変更する**: jvm / js / linuxX64 は ubuntu の CI で、macosArm64 は `shared/**` 変更時の macOS ジョブかローカル実行の証跡で検証する。
8. **P02 に Canonical Model と Avro スキーマの一致検査を追加する**(ADR-0004)。
9. **P03 の compose profiles を `core` / `cdc` / `iot` / `file` / `b2b` / `chaos` / `secure` に統一し、DoD を「profile ごとに healthy」にする**。
10. **Outbox / Command の方式を P06・P07 に反映する**(ADR-0006・0007)。

GitHub のマイルストーンと Issue は、改訂後のフェーズ一覧(P00〜P14 に P04a / P04b を含む計 16 件)で作成する(`scripts/bootstrap-github.sh`)。

## Alternatives Considered
- P04 を分割せず、サブ PR で分ける: マイルストーン単位で進捗が見えにくくなるため不採用。
- messaging-kafka も P04 で作る: 使われ方が決まる前の設計になり、P06・P07 で作り直すおそれがあるため不採用。

## Consequences(トレードオフ)
- フェーズが 1 つ増える。
- 各フェーズで E2E シナリオを保守する負担が増えるが、回帰の検出は早くなる。
