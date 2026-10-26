# iot-bridge(MQTT から Kafka への橋渡し。ADR-0039)のコンテナイメージ。order-service と同じ作り(ADR-0024 §7)。
# - ベースは images.env の JAVA_RUNTIME_IMAGE(distroless の Java 21、nonroot)。シェルがないので java を直接起動する。
# - 中身は `./gradlew :services:iot-bridge:app:installDist` の出力。デバイスの形式のスキーマと出力の契約のスキーマはクラスパスに含まれる。
# - コマンドは serve・reprocess-dlq。compose が引数で渡す(P11 ⑥)。
ARG JAVA_RUNTIME_IMAGE
FROM ${JAVA_RUNTIME_IMAGE}
WORKDIR /app
COPY build/install/app/lib/ /app/lib/
USER 65532:65532
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-cp", "/app/lib/*", "io.eia.iotbridge.app.MainKt"]
