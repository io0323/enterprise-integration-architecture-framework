plugins {
    id("eia.jvm-library")
    // テストのイベントの型(@Serializable)。本番のイベントの型は各サービスの adapters に置く
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Kafka の Producer 側(P06)と Consumer 側(P07)の共通部品(Framework 6。ADR-0025)。
// - CloudEvents binary mode のヘッダ(ce_*)と traceparent・correlationid
// - Avro の Serde(avro4k)。スキーマ ID は Apicurio と同じ wire format でペイロードの先頭に埋め込む(ADR-0007 §1・ADR-0025 §1)
// - Producer(冪等・acks=all。PRODUCER の span を作り、その traceparent をヘッダに入れる)
// - Consumer(P07。At-Least-Once・順序・リトライ → DLQ・CONSUMER の span。ADR-0028)
// Kafka・Avro を使ってよいのは、このモジュールと services の adapters / app だけ(Konsist)。
dependencies {
    api(project(":shared:kernel"))
    // 書き込みに使うスキーマの ID(起動時に解決したもの)と、受信時の書き手のスキーマ
    api(project(":platform:schema-registry"))
    // PRODUCER の span と Correlation ID(ADR-0018 §2)
    api(project(":platform:observability"))
    api(libs.kafka.clients)
    api(libs.avro4k.core)
    api(libs.avro)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.kotest.property)
    testImplementation(libs.ktor.client.mock)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.ktor.client.cio)
    // 自前の wire format と Apicurio の公式の Serde の相互運用を、双方向で確かめる(ADR-0025 §1)
    integrationTestImplementation(libs.apicurio.registry.avro.serde.kafka)
    // Consumer と冪等消費の記録(platform/inbox)を組み合わせた At-Least-Once の検証(ADR-0028)。テストだけの依存
    integrationTestImplementation(project(":platform:inbox"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
}
