package io.eia.buildlogic

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File

/**
 * Debezium(Kafka Connect)のコンテナを起動する統合テストを、同じビルドの中で同時に 1 つだけ動かすための共有のビルドサービス(#126)。
 *
 * 統合テストのタスクは、`org.gradle.parallel=true` でモジュールごとに並列に動く。Kafka Connect を使う統合テストは、それぞれ
 * PostgreSQL・Kafka・Kafka Connect のコンテナを立てるので、同じランナーで同時に動くと CPU とメモリを取り合い、Debezium の取り込みが
 * テストの待ち時間を超えることがあった。`maxParallelUsages = 1` で登録し、該当する統合テストのタスクが `usesService` で使う。
 * 状態は持たない(Gradle の同時実行の制限にだけ使う)。
 */
abstract class CdcContainersGate : BuildService<BuildServiceParameters.None>

const val CDC_CONTAINERS_GATE: String = "cdcContainersGate"

/** [sourceDirectory] の Kotlin のソースが Kafka Connect のテスト用コンテナ(`KafkaConnectContainer`)を使うか。 */
fun usesKafkaConnect(sourceDirectory: File): Boolean =
    sourceDirectory.isDirectory &&
        sourceDirectory
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .any { "KafkaConnectContainer" in it.readText() }
