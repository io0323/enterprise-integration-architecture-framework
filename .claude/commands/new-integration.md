---
description: 新しい連携を Framework 標準に沿って追加する(Contract First)
argument-hint: <連携の説明 例: "shipping が出荷完了イベントを発行し CRM に通知">
---
次の連携を追加する: $ARGUMENTS

1. docs/architecture/EIA-Framework.md 3 章の採用基準(同期性→データ量→鮮度→相手制約→運用能力)で連携方式とパターンを選定し、根拠を示す。
2. docs/standards/INTEGRATION_STANDARDS.md に従い、連携ID・命名・Tier・データ分類を決める。
3. contracts/ に契約(OpenAPI / AsyncAPI+Avro / Proto / ファイル仕様)と contracts/catalog/<id>.yaml を追加。
4. 必須装備(同期: Timeout/Retry/CB/Fallback、非同期: 冪等/DLQ/Replay、全: Correlation ID/Tracing/認証)を実装。
5. Unit / Contract / Integration テストを追加し、./gradlew build を通す。
方式選定の段階で一度止まり、私の確認を得てから契約作成に進むこと。
