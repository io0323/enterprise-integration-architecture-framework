# legacy-order-acl(レガシーの受注の CDC の Anti-Corruption Layer。ADR-0026)のコンテナイメージ。order-service と同じ作り(ADR-0024 §7)。
# - ベースは images.env の JAVA_RUNTIME_IMAGE(distroless の Java 21、nonroot)。シェルがないので java を直接起動する。
# - 中身は `./gradlew :services:legacy-order-acl:app:installDist` の出力。make up PROFILE=cdc が先に作る。
ARG JAVA_RUNTIME_IMAGE
FROM ${JAVA_RUNTIME_IMAGE}
WORKDIR /app
COPY build/install/app/lib/ /app/lib/
USER 65532:65532
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-cp", "/app/lib/*", "io.eia.legacyorderacl.app.MainKt"]
