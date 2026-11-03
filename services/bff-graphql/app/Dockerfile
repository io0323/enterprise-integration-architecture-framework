# bff-graphql(画面のための集約。ADR-0043)のコンテナイメージ。order-service と同じ作り(ADR-0024 §7)。
# - ベースは images.env の JAVA_RUNTIME_IMAGE(distroless の Java 21、nonroot)。シェルがないので java を直接起動する。
# - 中身は `./gradlew :services:bff-graphql:app:installDist` の出力。make up PROFILE=bff が先に作る。
ARG JAVA_RUNTIME_IMAGE
FROM ${JAVA_RUNTIME_IMAGE}
WORKDIR /app
COPY build/install/app/lib/ /app/lib/
USER 65532:65532
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-cp", "/app/lib/*", "io.eia.bffgraphql.app.MainKt"]
