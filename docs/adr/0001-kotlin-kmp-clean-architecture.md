# ADR-0001: Kotlin / KMP / Clean Architecture の採用
- Status: Accepted
- Date: 2026-09-25
- Framework 参照章: 1.9, 19

## Context
10 年以上利用する連携参照実装として、型安全性・JVM エコシステム(Kafka/Debezium/OTel)との親和性・
複数実行環境(サーバ / エッジ / ブラウザ)への連携 SDK 提供が求められる。

## Decision
- 言語は Kotlin。サーバは JVM、共通モデル・ドメイン・SDK は KMP(commonMain)。
- 各サービスは Clean Architecture(domain / application を KMP、adapters / app を JVM)。
- basePackage は `io.eia`(組織決定後に変更可)。

## Alternatives Considered
- Java + Spring Boot: 実績豊富だが KMP による SDK 共有ができない。
- Go: 軽量だが Kafka/CDC エコシステムと型表現力で劣る。

## Consequences
- domain/application がフレームワーク非依存となり、実行基盤変更(Evolutionary Architecture)に強い。
- Kafka 等 JVM 専用ライブラリは adapters に閉じ込める必要があり、モジュール数が増える。
