# Security Policy

## 脆弱性の報告

脆弱性を見つけた場合は、**公開の Issue・Pull Request・Discussion には書かず**、GitHub の Private vulnerability reporting で非公開で報告してください。

- 報告の窓口: [Report a vulnerability](https://github.com/io0323/enterprise-integration-architecture-framework/security/advisories/new)(リポジトリの **Security** タブ → **Report a vulnerability**)
- 報告に含めてほしいこと: 影響を受けるファイルやモジュール、再現の手順、想定される影響
- 報告の内容は、修正して公開するまで非公開のまま扱います。

Please report vulnerabilities privately via GitHub's [Private vulnerability reporting](https://github.com/io0323/enterprise-integration-architecture-framework/security/advisories/new). Do not open a public issue.

## 対象

- 対象は、このリポジトリの `main` ブランチのコードと設定です。リリース版はありません。
- このリポジトリはローカルで動かす参照実装です。ADR-0008 で範囲外とした項目(ローカル基盤の平文の通信、基盤の管理 API が未認証であることなど)は、既知の制約として扱います。ただし、その影響がローカル環境の外に及ぶ場合は報告してください。
- `make env` が開発者の手元で生成する資格情報(`infra/local/.env`・`infra/local/secrets/`)は、リポジトリに含まれません。テストのコードにある JWT・PEM の断片などは、検証のためのダミーの値です(ADR-0020 §4)。
