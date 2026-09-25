# Enterprise Integration Architecture Framework

[Enterprise Integration Architecture Framework](docs/architecture/EIA-Framework.md) のローカル参照実装。
Kotlin / Kotlin Multiplatform / Clean Architecture、Cloud Agnostic(Docker Compose で完結)。

- ロードマップ: [docs/implementation/ROADMAP.md](docs/implementation/ROADMAP.md)
- モジュール設計: [docs/implementation/MODULE_DESIGN.md](docs/implementation/MODULE_DESIGN.md)
- 標準: [docs/standards/](docs/standards/)
- ADR: [docs/adr/](docs/adr/)
- Claude Code 用プロンプト: [docs/prompts/PROMPTS.md](docs/prompts/PROMPTS.md)

## Quick Start(P03 以降)
```bash
make up
./gradlew build
./gradlew :services:order:app:run
```
