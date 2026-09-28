# Enterprise Integration Architecture Framework

[Enterprise Integration Architecture Framework](docs/architecture/EIA-Framework.md) のローカル参照実装。
Kotlin / Kotlin Multiplatform / Clean Architecture、Cloud Agnostic(Docker Compose で完結)。

- ロードマップ: [docs/implementation/ROADMAP.md](docs/implementation/ROADMAP.md)
- モジュール設計: [docs/implementation/MODULE_DESIGN.md](docs/implementation/MODULE_DESIGN.md)
- 標準: [docs/standards/](docs/standards/)
- ADR: [docs/adr/](docs/adr/)
- Claude Code 用プロンプト: [docs/prompts/PROMPTS.md](docs/prompts/PROMPTS.md)

## 開発環境の前提
- clone したら最初に `make setup` を実行する。main / master への直接 push を拒否する Git フック(`scripts/git-hooks/pre-push`)を登録し、`push.default=simple` を設定する(どちらもリポジトリ単位の設定)。`simple` では、upstream とブランチ名が違うときに push が拒否されるため、作業ブランチの push が誤って main に送られない。
- **JDK 21(arm64)** を使う。Apple Silicon で x86_64 の JDK(Rosetta)を使うと、Kotlin/Native がホストを `macos_x64` と判定し、`macosArm64` のテストが動かない。
- `JAVA_HOME` の JDK のバージョンとアーキテクチャは、次のコマンドで確認する(`arm64` と表示されること)。
  ```bash
  echo $JAVA_HOME
  "$JAVA_HOME/bin/java" -version
  file "$JAVA_HOME/bin/java"
  /usr/libexec/java_home -V   # インストール済み JDK とアーキテクチャの一覧(macOS)
  ```
- Apple Silicon の Mac で x86_64 の JDK を使って Gradle を起動すると、**ビルドは構成時にエラーで失敗する**(`build-logic` の `requireArm64JdkOnAppleSilicon`)。x86_64 の JDK では macosArm64 のテストが黙ってスキップされ、`./gradlew build` が成功しても検証が漏れるため。エラーになったら、arm64 の JDK 21 に `JAVA_HOME` を向け、`./gradlew --stop` で x86_64 の Gradle デーモンを止めてからやり直す。Intel Mac と Linux では検査しない。
- ビルドに使う JDK 21 の toolchain は、foojay(`org.gradle.toolchains.foojay-resolver-convention`)が自動で取得する(`~/.gradle/jdks`)。ただし Gradle 自体を起動する JVM は `JAVA_HOME` の JDK なので、上記のとおり arm64 の JDK を指定する。

## Quick Start(P03 以降)
ローカル基盤(Docker Compose)の profile・ポート一覧・認証情報は [infra/local/README.md](infra/local/README.md) を参照。

```bash
make up                  # ローカル基盤(core)を起動。make up PROFILE=cdc などで profile を追加
make verify              # 全コンテナが healthy で、各機能が疎通することを検査
./gradlew build
./gradlew :services:order:app:run
```

このリポジトリにはまだ LICENSE がないため、著作権法上すべての権利が作者に留保され、他の人はコードを利用・複製・改変・再配布できません(GitHub の規約による閲覧と fork は可能です)。
