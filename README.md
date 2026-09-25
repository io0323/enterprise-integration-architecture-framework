# Enterprise Integration Architecture Framework

[Enterprise Integration Architecture Framework](docs/architecture/EIA-Framework.md) のローカル参照実装。
Kotlin / Kotlin Multiplatform / Clean Architecture、Cloud Agnostic(Docker Compose で完結)。

- ロードマップ: [docs/implementation/ROADMAP.md](docs/implementation/ROADMAP.md)
- モジュール設計: [docs/implementation/MODULE_DESIGN.md](docs/implementation/MODULE_DESIGN.md)
- 標準: [docs/standards/](docs/standards/)
- ADR: [docs/adr/](docs/adr/)
- Claude Code 用プロンプト: [docs/prompts/PROMPTS.md](docs/prompts/PROMPTS.md)

## 開発環境の前提
- **JDK 21(arm64)** を使う。Apple Silicon で x86_64 の JDK(Rosetta)を使うと、Kotlin/Native がホストを `macos_x64` と判定し、`macosArm64` のテストが動かない。
- `JAVA_HOME` の JDK のバージョンとアーキテクチャは、次のコマンドで確認する(`arm64` と表示されること)。
  ```bash
  echo $JAVA_HOME
  "$JAVA_HOME/bin/java" -version
  file "$JAVA_HOME/bin/java"
  /usr/libexec/java_home -V   # インストール済み JDK とアーキテクチャの一覧(macOS)
  ```
- ビルドに使う JDK 21 の toolchain は、foojay(`org.gradle.toolchains.foojay-resolver-convention`)が自動で取得する(`~/.gradle/jdks`)。ただし Gradle 自体を起動する JVM は `JAVA_HOME` の JDK なので、上記のとおり arm64 の JDK を指定する。

## Quick Start(P03 以降)
```bash
make up
./gradlew build
./gradlew :services:order:app:run
```
