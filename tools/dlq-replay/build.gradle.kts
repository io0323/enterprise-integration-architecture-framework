plugins {
    id("eia.jvm-service")
}

// DLQ の Replay の CLI(`make dlq-replay ARGS="--topic <dlq> --limit <n> [--execute]"`。Framework 13.1。ADR-0028 §6)。
// 手順は docs/runbooks/event-dlq-replay.md。終了コードを返すため、`run` ではなく `installDist` の起動スクリプトから実行する
// (scripts/dlq-replay.sh)。Kafka のクライアントは platform/messaging-kafka の中だけで使う(Konsist)。
application {
    mainClass.set("io.eia.tools.dlqreplay.MainKt")
    applicationName = "dlq-replay"
}

dependencies {
    implementation(project(":platform:messaging-kafka"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.core)
}
